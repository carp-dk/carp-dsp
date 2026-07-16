package carp.dsp.demo.eval

import carp.dsp.core.application.authoring.mapper.WorkflowDescriptorImporter
import carp.dsp.core.application.plan.DefaultExecutionPlanner
import carp.dsp.core.infrastructure.serialization.WorkflowYamlCodec
import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import dk.cachet.carp.analytics.application.plan.PlanIssueSeverity
import dk.cachet.carp.common.application.UUID
import java.io.File

/**
 * Reuse eval.
 *
 * Quantifies what a researcher must change to reuse the Use Case 1 gait workflow on a
 * second cohort. Compares the shipped MS workflow with an HA-cohort variant that differs
 * only in the `import-data` recording selector, and records:
 *   - which step(s) changed and how many YAML lines changed
 *   - how many of the seven downstream steps were modified (expected: 0)
 *   - whether the planner accepts the adapted workflow with no ERROR issues
 *   - whether the resolved plan is structurally identical (same step UUIDs + order),
 *     i.e. the DAG did not need manual re-specification
 *
 * All of this is static (no pipeline run): the reuse claim is about authoring and
 * planning, not execution. A separate end-to-end run on the HA recording (optional)
 * confirms the downstream steps also execute unchanged on the new data.
 *
 * Run:
 *   ./gradlew :carp.dsp.demo:evalReuse
 *
 * Writes eval_results/reuse-report.txt and eval_results/reuse.csv.
 */

private val REUSE_NAMESPACE: UUID = UUID.parse("d3b7f2a0-0000-5000-8000-6d6f62676170")

private const val UC1 = "workflows/mobgap-gait-analysis.yaml"
private const val UC2 = "workflows/mobgap-gait-analysis-ha.yaml"
private const val STEP_ANCHOR = "\n  - id: \""

fun main() {
    val codec = WorkflowYamlCodec()
    val uc1Yaml = loadResource(UC1)
    val uc2Yaml = loadResource(UC2)

    // ── Text-level reuse metrics (per-step scoped diff) ──────────────────────────
    val uc1Steps = stepBlocks(uc1Yaml)
    val uc2Steps = stepBlocks(uc2Yaml)
    val changedSteps = uc2Steps.keys.filter { uc1Steps[it] != uc2Steps[it] } +
        uc1Steps.keys.filter { it !in uc2Steps }
    val downstreamModified = changedSteps.filter { it != "import-data" }
    val (added, removed) = lineDelta(uc1Yaml, uc2Yaml)

    // ── Plan-level reuse metrics ─────────────────────────────────────────────────
    val uc1Plan = planWorkflow(codec, uc1Yaml)
    val uc2Plan = planWorkflow(codec, uc2Yaml)
    val uc2Errors = uc2Plan.issues.filter { it.severity == PlanIssueSeverity.ERROR }
    val plannerAccepts = uc2Errors.isEmpty()
    val sameDag = fingerprint(uc1Plan) == fingerprint(uc2Plan)

    val report = buildString {
        appendLine("=".repeat(70))
        appendLine("Reuse eval - ${java.time.Instant.now()}")
        appendLine("UC1: mobgap-gait-analysis.yaml (MS cohort)")
        appendLine("UC2: mobgap-gait-analysis-ha.yaml (HA cohort)")
        appendLine()
        appendLine("Authoring change:")
        appendLine("  Steps changed:            ${changedSteps.ifEmpty { listOf("<none>") }}")
        appendLine("  Downstream steps modified: ${downstreamModified.size} of 7 (expected 0)")
        appendLine("  YAML lines added:          $added")
        appendLine("  YAML lines removed:        $removed")
        appendLine()
        appendLine("Planner:")
        appendLine("  UC2 accepted (0 ERROR issues):     $plannerAccepts")
        appendLine("  Resolved plan identical to UC1:    $sameDag  (same step UUIDs + topological order)")
        appendLine("  -> no manual DAG re-specification; downstream type compatibility re-verified automatically")
        appendLine()
        val claimHolds = downstreamModified.isEmpty() && plannerAccepts && sameDag &&
            changedSteps.toSet() == setOf("import-data")
        appendLine("Reuse claim holds (only import-data changed, planner accepts, DAG unchanged): $claimHolds")
        appendLine("=".repeat(70))
    }
    println(report)

    val dir = evalResultsDir()
    dir.resolve("reuse-report.txt").appendText(report + "\n")
    dir.resolve("reuse.csv").writeText(
        buildString {
            appendLine("dimension,carp")
            appendLine("steps_changed,${changedSteps.joinToString("|").ifEmpty { "none" }}")
            appendLine("downstream_steps_modified,${downstreamModified.size}")
            appendLine("yaml_lines_added,$added")
            appendLine("yaml_lines_removed,$removed")
            appendLine("planner_accepts,$plannerAccepts")
            appendLine("plan_identical_to_uc1,$sameDag")
        }
    )
    println("Wrote reuse-report.txt and reuse.csv to: ${dir.absolutePath}")
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
    val aLines = a.lines()
    val bLines = b.lines()
    // ignore the leading header comment block of the variant when counting
    val aBag = aLines.filterNot { it.startsWith("#") }.groupingBy { it }.eachCount().toMutableMap()
    val bBag = bLines.filterNot { it.startsWith("#") }.groupingBy { it }.eachCount().toMutableMap()
    var added = 0
    bBag.forEach { (line, n) -> added += (n - (aBag[line] ?: 0)).coerceAtLeast(0) }
    var removed = 0
    aBag.forEach { (line, n) -> removed += (n - (bBag[line] ?: 0)).coerceAtLeast(0) }
    return added to removed
}

private fun planWorkflow(codec: WorkflowYamlCodec, yaml: String): ExecutionPlan {
    val descriptor = codec.decodeOrThrow(yaml)
    val definition = WorkflowDescriptorImporter(REUSE_NAMESPACE).import(descriptor)
    return DefaultExecutionPlanner().plan(definition)
}

/** Claim-level plan fingerprint: step order + UUIDs + env refs. Excludes planId. */
private fun fingerprint(plan: ExecutionPlan): String =
    plan.steps.withIndex().joinToString("\n") { (i, s) -> "$i|${s.metadata.id}|${s.environmentRef}" }

private object ReuseResourceAnchor

private fun loadResource(path: String): String =
    ReuseResourceAnchor::class.java.classLoader.getResource(path)?.readText()
        ?: throw IllegalStateException("Resource not found: $path")

private fun evalResultsDir(): File {
    val classPath = ReuseResourceAnchor::class.java.protectionDomain.codeSource.location.toURI().path
    val projectRoot = File(classPath).parentFile?.parentFile?.parentFile?.parentFile?.parentFile
        ?: throw IllegalStateException("Cannot determine project root")
    return projectRoot.resolve("eval_results").apply { mkdirs() }
}
