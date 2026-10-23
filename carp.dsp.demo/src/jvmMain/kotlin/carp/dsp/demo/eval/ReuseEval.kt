package carp.dsp.demo.eval

import carp.dsp.core.application.authoring.mapper.WorkflowDescriptorImporter
import carp.dsp.core.application.authoring.resolve.UsesResolver
import carp.dsp.core.application.plan.DefaultExecutionPlanner
import carp.dsp.core.application.run.WorkflowExecutor
import carp.dsp.core.infrastructure.serialization.WorkflowYamlCodec
import carp.dsp.demo.io.DemoIo
import carp.dsp.demo.io.DemoRun
import carp.dsp.demo.io.MobgapScripts
import carp.dsp.steps.ClasspathStepLibrary
import dk.cachet.carp.analytics.application.execution.ExecutionStatus
import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import dk.cachet.carp.analytics.application.plan.PlanIssueSeverity
import dk.cachet.carp.common.application.UUID
import java.nio.file.Path
import java.util.Locale

/**
 * Retargeting eval (paper: Evaluation / Workflow retargeting, feature AU3).
 *
 * Measures what it takes to run an existing workflow on other data, in two cases:
 *
 *   - **cohort**: the Use Case 1 gait workflow (MS cohort of mobgap's LabExampleDataset)
 *     retargeted to the HA cohort - Use Case 2. Only the recording-selection
 *     arguments of `import-data` change.
 *   - **period**: the library-step activity workflow over the open Fitbit dataset
 *     (Zenodo 53894, 12 Apr - 12 May 2016) retargeted to the dataset's other
 *     collection period (12 Mar - 11 Apr 2016). Only the arguments of the two fetch
 *     steps change.
 *
 * For each case it records, statically:
 *   - which steps changed and how many YAML lines changed
 *   - how many steps outside the expected ones were modified (expected: 0)
 *   - whether the planner accepts the variant with no ERROR issues
 *   - whether the resolved plan is structurally identical (same step UUIDs + order)
 *
 * and, unless run with the `static` argument, executes base and variant end to end:
 *   - whether both runs succeed, and how many steps succeeded
 *   - in how many steps the outputs differ from the base run, i.e. how many steps
 *     computed on the new data rather than repeating the old result
 *   - for the cohort case, the walking-bout count and total walking duration of each run
 *
 * Execution needs pixi and network access: the gait case downloads mobgap's example
 * dataset and the period case two Zenodo archives (~45 MB).
 *
 * Run:
 *   ./gradlew :carp.dsp.demo:evalReuse [-Pargs=static] [-PpaperDir=<paper repo>]
 *
 * Writes eval_results/reuse-report.txt (appended), reuse.csv and, after an executed
 * run, reuse-numbers.tex (LaTeX macros, published to the paper's generated/ with
 * -PpaperDir).
 */

private val REUSE_NAMESPACE: UUID = UUID.parse("d3b7f2a0-0000-5000-8000-6d6f62676170")
private const val STEP_ANCHOR = "\n  - id: \""
private const val DMO_OUTPUT = "steps/06_dmo_aggregation/outputs/aggregated-dmos-csv.csv"

private data class ReuseCase(
    /** Key used in the CSV and the LaTeX macro names, e.g. "cohort" -> `\ReuseCohort...`. */
    val key: String,
    val description: String,
    val base: String,
    val variant: String,
    /** The steps the retargeting is meant to change; anything else changing is a failure. */
    val expectedChanged: Set<String>,
    /** Whether the gait scripts must be staged in the run root before execution. */
    val stagesMobgapScripts: Boolean = false,
)

private val CASES = listOf(
    ReuseCase(
        key = "cohort",
        description = "Gait pipeline, MS -> HA cohort (mobgap LabExampleDataset)",
        base = "workflows/mobgap-gait-analysis.yaml",
        variant = "workflows/mobgap-gait-analysis-ha.yaml",
        expectedChanged = setOf("import-data"),
        stagesMobgapScripts = true,
    ),
    ReuseCase(
        key = "period",
        description = "Activity workflow, Apr-May -> Mar-Apr 2016 (Zenodo 53894)",
        base = "workflows/wf-activity-summary-v2.yaml",
        variant = "workflows/wf-activity-summary-v2-2016-03.yaml",
        expectedChanged = setOf("fetch-steps", "fetch-hr"),
    ),
)

private data class StaticResult(
    val case: ReuseCase,
    val totalSteps: Int,
    val changedSteps: List<String>,
    val unexpectedChanges: List<String>,
    val linesAdded: Int,
    val linesRemoved: Int,
    val plannerAccepts: Boolean,
    val sameDag: Boolean,
) {
    val holds: Boolean get() =
        unexpectedChanges.isEmpty() && plannerAccepts && sameDag && changedSteps.toSet() == case.expectedChanged
}

private data class RunOutcome(
    val succeeded: Boolean,
    val stepsSucceeded: Int,
    val stepsTotal: Int,
    /** step id -> sorted output hashes; ids match across base and variant when the plans do */
    val outputHashes: Map<UUID, List<String>>,
    val runRoot: Path,
    val failures: List<String>,
)

private data class ExecutedResult(
    val base: RunOutcome,
    val variant: RunOutcome,
    /** Steps whose outputs differ between the two runs. */
    val stepsWithNewOutputs: Int,
    /** Aggregated DMOs of each run, for the cohort case: column -> value (empty when not produced). */
    val baseDmos: Map<String, String> = emptyMap(),
    val variantDmos: Map<String, String> = emptyMap(),
)

private val STEP_LIBRARY = ClasspathStepLibrary()

fun main(args: Array<String>) {
    val execute = "static" !in args
    val codec = WorkflowYamlCodec()

    val statics = CASES.map { staticResult(codec, it) }
    val executed = if (execute) CASES.associateWith { executeCase(it) } else emptyMap()

    val report = buildString {
        appendLine("=".repeat(70))
        appendLine("Retargeting eval - ${java.time.Instant.now()}")
        statics.forEach { s ->
            appendLine()
            appendLine("Case '${s.case.key}': ${s.case.description}")
            appendLine("  ${s.case.base} -> ${s.case.variant}")
            appendLine("  Steps changed:          ${s.changedSteps.ifEmpty { listOf("<none>") }}")
            appendLine("  Unexpected changes:     ${s.unexpectedChanges.size} of ${s.totalSteps} steps (expected 0)")
            appendLine("  YAML lines added/removed: ${s.linesAdded} / ${s.linesRemoved}")
            appendLine("  Planner accepts variant: ${s.plannerAccepts}")
            appendLine("  Plan identical to base:  ${s.sameDag}  (same step UUIDs + topological order)")
            appendLine("  Static claim holds:      ${s.holds}")
            executed[s.case]?.let { e ->
                appendLine("  Executed:")
                appendLine("    base:    ${outcomeLine(e.base)}")
                appendLine("    variant: ${outcomeLine(e.variant)}")
                (e.base.failures + e.variant.failures).forEach { appendLine("      - $it") }
                appendLine("    Steps whose outputs changed: ${e.stepsWithNewOutputs} of ${e.base.stepsTotal}")
                if (e.baseDmos.isNotEmpty() || e.variantDmos.isNotEmpty()) {
                    appendLine("    Walking bouts:          ${e.baseDmos[BOUT_COUNT] ?: "n/a"} -> ${e.variantDmos[BOUT_COUNT] ?: "n/a"}")
                    appendLine(
                        "    Walking duration (min): ${e.baseDmos[WALKING_MINUTES] ?: "n/a"} -> " +
                            (e.variantDmos[WALKING_MINUTES] ?: "n/a")
                    )
                }
            }
        }
        if (!execute) {
            appendLine()
            appendLine("Static run only (-Pargs=static): nothing was executed.")
        }
        appendLine("=".repeat(70))
    }
    println(report)

    val dir = DemoIo.evalResultsDir()
    dir.resolve("reuse-report.txt").appendText(report + "\n")
    dir.resolve("reuse.csv").writeText(csv(statics, executed))
    println("Wrote reuse-report.txt and reuse.csv to: ${dir.absolutePath}")

    if (execute) {
        val numbers = dir.resolve("reuse-numbers.tex").apply { writeText(latexNumbers(statics, executed)) }
        DemoIo.publishToPaper(numbers, "generated/reuse-numbers.tex")
    }
}

// ── Static comparison ───────────────────────────────────────────────────────────

private fun staticResult(codec: WorkflowYamlCodec, case: ReuseCase): StaticResult {
    val baseYaml = DemoIo.loadResource(case.base)
    val variantYaml = DemoIo.loadResource(case.variant)
    val baseSteps = stepBlocks(baseYaml)
    val variantSteps = stepBlocks(variantYaml)
    val changed = variantSteps.keys.filter { baseSteps[it] != variantSteps[it] } +
        baseSteps.keys.filter { it !in variantSteps }
    val (added, removed) = lineDelta(baseYaml, variantYaml)

    val basePlan = planWorkflow(codec, baseYaml)
    val variantPlan = planWorkflow(codec, variantYaml)
    return StaticResult(
        case = case,
        totalSteps = baseSteps.size,
        changedSteps = changed,
        unexpectedChanges = changed.filter { it !in case.expectedChanged },
        linesAdded = added,
        linesRemoved = removed,
        plannerAccepts = variantPlan.issues.none { it.severity == PlanIssueSeverity.ERROR },
        sameDag = fingerprint(basePlan) == fingerprint(variantPlan),
    )
}

/** Split a workflow YAML into stepId -> raw block text, using the two-space step anchor. */
private fun stepBlocks(yaml: String): Map<String, String> {
    val blocks = LinkedHashMap<String, String>()
    var i = yaml.indexOf(STEP_ANCHOR)
    while (i >= 0) {
        val start = i + 1
        val idStart = start + "  - id: \"".length
        val idEnd = yaml.indexOf('"', idStart)
        val id = yaml.substring(idStart, idEnd)
        val next = yaml.indexOf(STEP_ANCHOR, start)
        val end = if (next < 0) yaml.length else next + 1
        blocks[id] = yaml.substring(start, end)
        i = next
    }
    return blocks
}

/** Count lines present in one text but not the other (additive/removed line counts). */
private fun lineDelta(a: String, b: String): Pair<Int, Int> {
    // comments are not part of the workflow, wherever they are indented
    val aBag = a.lines().filterNot { it.trimStart().startsWith("#") }.groupingBy { it }.eachCount()
    val bBag = b.lines().filterNot { it.trimStart().startsWith("#") }.groupingBy { it }.eachCount()
    val added = bBag.entries.sumOf { (line, n) -> (n - (aBag[line] ?: 0)).coerceAtLeast(0) }
    val removed = aBag.entries.sumOf { (line, n) -> (n - (bBag[line] ?: 0)).coerceAtLeast(0) }
    return added to removed
}

private fun planWorkflow(codec: WorkflowYamlCodec, yaml: String): ExecutionPlan {
    // Library references (`uses:`) are expanded first; a workflow with none is unchanged.
    val descriptor = UsesResolver(STEP_LIBRARY).resolve(codec.decodeOrThrow(yaml)).workflow
    val definition = WorkflowDescriptorImporter(REUSE_NAMESPACE).import(descriptor)
    return DefaultExecutionPlanner().plan(definition)
}

/** Claim-level plan fingerprint: step order + UUIDs + env refs. Excludes planId. */
private fun fingerprint(plan: ExecutionPlan): String =
    plan.steps.withIndex().joinToString("\n") { (i, s) -> "$i|${s.metadata.id}|${s.environmentRef}" }

// ── Execution ───────────────────────────────────────────────────────────────────

// Present for every recording; speed columns are per bout length and empty when a
// recording has no bout of that length.
private const val BOUT_COUNT = "wb_all__count"
private const val WALKING_MINUTES = "total_walking_duration_min"

private fun executeCase(case: ReuseCase): ExecutedResult {
    val base = run(case, case.base, "base")
    val variant = run(case, case.variant, "variant")
    val changedOutputs = base.outputHashes.keys.count { base.outputHashes[it] != variant.outputHashes[it] }
    return ExecutedResult(
        base = base,
        variant = variant,
        stepsWithNewOutputs = changedOutputs,
        baseDmos = if (case.stagesMobgapScripts) dmos(base.runRoot) else emptyMap(),
        variantDmos = if (case.stagesMobgapScripts) dmos(variant.runRoot) else emptyMap(),
    )
}

private fun run(case: ReuseCase, resource: String, role: String): RunOutcome {
    val dir = DemoRun.freshResultsDir(DemoIo.demoResultsDir("reuse_${case.key}_$role").toPath())
    val loaded = DemoRun.loadWorkflow(resource, dir)
    val executor = WorkflowExecutor.filesystem(STEP_LIBRARY, dir)
    val prepared = executor.prepare(loaded.source, loaded.descriptor)
    val runId = UUID.randomUUID()
    val runRoot = MobgapScripts.runRoot(dir, prepared.plan, runId)
    if (case.stagesMobgapScripts) MobgapScripts.stage(runRoot)

    println("Retargeting eval: running $resource ($role, ${prepared.plan.steps.size} steps)...")
    val report = executor.run(prepared, runId)
    return RunOutcome(
        succeeded = report.status == ExecutionStatus.SUCCEEDED,
        stepsSucceeded = report.stepResults.count { it.status == ExecutionStatus.SUCCEEDED },
        stepsTotal = prepared.plan.steps.size,
        outputHashes = report.stepResults.associate { r ->
            r.stepMetadata.id to r.outputs.orEmpty().mapNotNull { it.sha256 }.sorted()
        },
        runRoot = runRoot,
        failures = report.issues.map { "$role: ${it.message}" },
    )
}

/** The first data row of the gait pipeline's aggregated DMOs, by column; empty if absent. */
private fun dmos(runRoot: Path): Map<String, String> {
    val file = runRoot.resolve(DMO_OUTPUT).toFile()
    if (!file.isFile) return emptyMap()
    val lines = file.readLines().filter { it.isNotBlank() }
    if (lines.size < 2) return emptyMap()
    return lines[0].split(',').zip(lines[1].split(',')).toMap()
}

private fun outcomeLine(o: RunOutcome): String =
    "${if (o.succeeded) "SUCCEEDED" else "FAILED"}, ${o.stepsSucceeded} / ${o.stepsTotal} steps succeeded"

// ── Outputs ─────────────────────────────────────────────────────────────────────

private fun csv(statics: List<StaticResult>, executed: Map<ReuseCase, ExecutedResult>): String = buildString {
    appendLine("case,dimension,value")
    statics.forEach { s ->
        val k = s.case.key
        appendLine("$k,steps_total,${s.totalSteps}")
        appendLine("$k,steps_changed,${s.changedSteps.joinToString("|").ifEmpty { "none" }}")
        appendLine("$k,unexpected_changes,${s.unexpectedChanges.size}")
        appendLine("$k,yaml_lines_added,${s.linesAdded}")
        appendLine("$k,yaml_lines_removed,${s.linesRemoved}")
        appendLine("$k,planner_accepts,${s.plannerAccepts}")
        appendLine("$k,plan_identical_to_base,${s.sameDag}")
        executed[s.case]?.let { e ->
            appendLine("$k,base_succeeded,${e.base.succeeded}")
            appendLine("$k,variant_succeeded,${e.variant.succeeded}")
            appendLine("$k,steps_with_new_outputs,${e.stepsWithNewOutputs}")
            e.baseDmos[WALKING_MINUTES]?.let { appendLine("$k,base_walking_minutes,$it") }
            e.variantDmos[WALKING_MINUTES]?.let { appendLine("$k,variant_walking_minutes,$it") }
            e.baseDmos[BOUT_COUNT]?.let { appendLine("$k,base_walking_bouts,$it") }
            e.variantDmos[BOUT_COUNT]?.let { appendLine("$k,variant_walking_bouts,$it") }
        }
    }
}

/**
 * The headline numbers as LaTeX macros, e.g. `\ReuseCohortStepsChanged`, so the text
 * quotes the run. Use with `\input{generated/reuse-numbers.tex}`.
 */
private fun latexNumbers(statics: List<StaticResult>, executed: Map<ReuseCase, ExecutedResult>): String = buildString {
    appendLine("% GENERATED by ReuseEval - do not edit; run :carp.dsp.demo:evalReuse")
    fun macro(name: String, value: Any) = appendLine("\\newcommand{\\$name}{$value}")
    statics.forEach { s ->
        val p = "Reuse" + s.case.key.replaceFirstChar { it.uppercase() }
        macro("${p}StepsTotal", s.totalSteps)
        macro("${p}StepsChanged", s.changedSteps.size)
        macro("${p}UnexpectedChanges", s.unexpectedChanges.size)
        macro("${p}LinesAdded", s.linesAdded)
        executed[s.case]?.let { e ->
            macro("${p}RunsSucceeded", listOf(e.base, e.variant).count { it.succeeded })
            macro("${p}StepsWithNewOutputs", e.stepsWithNewOutputs)
            e.baseDmos[BOUT_COUNT]?.let { macro("${p}BaseWalkingBouts", it) }
            e.variantDmos[BOUT_COUNT]?.let { macro("${p}VariantWalkingBouts", it) }
            e.baseDmos[WALKING_MINUTES]?.toDoubleOrNull()?.let { macro("${p}BaseWalkingMinutes", twoDecimals(it)) }
            e.variantDmos[WALKING_MINUTES]?.toDoubleOrNull()?.let { macro("${p}VariantWalkingMinutes", twoDecimals(it)) }
        }
    }
}

/** Locale-independent, so the decimal is always a point. */
private fun twoDecimals(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
