package carp.dsp.demo.eval

import carp.dsp.core.application.authoring.mapper.WorkflowDescriptorImporter
import carp.dsp.core.application.execution.ExecutionLogger
import carp.dsp.core.application.packaging.PackageBuilder
import carp.dsp.core.application.plan.DefaultExecutionPlanner
import carp.dsp.core.infrastructure.execution.DefaultPlanExecutor
import carp.dsp.core.infrastructure.execution.FileSystemArtefactStore
import carp.dsp.core.infrastructure.execution.workspace.DefaultWorkspaceManager
import carp.dsp.core.infrastructure.serialization.WorkflowYamlCodec
import dk.cachet.carp.common.application.UUID
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Use Case 1 run (paper: Fig D orchestration overhead + Reproducibility output determinism, same machine).
 *
 * Per run, it records:
 * - framework phase timings: decode, import, plan, validate, workspace staging, bundle
 * - per-step total duration (via [ExecutionLogger])
 * - per-step script self-time, parsed from `EVAL_SCRIPT_SECONDS=` lines the mobgap
 *   scripts print to stderr; overhead = step total - script self-time
 *   (includes pixi/interpreter startup, i.e. environment overhead)
 * - size + SHA-256 of every produced output file
 *
 * Across runs, it compares output hashes (same-machine output determinism).
 * NOTE: run 1 includes first-time environment setup and dataset download -
 * use run 2+ for steady-state timings.
 *
 * Run:
 *   ./gradlew :carp.dsp.demo:jvmRun -PmainClass=carp.dsp.demo.eval.MobgapTimedEvalKt
 * Optional first arg: number of runs (default 2).
 *
 * Results are printed and appended to `<project>/eval_results/mobgap-timing.txt`.
 */

private val EVAL_NAMESPACE: UUID = UUID.parse("d3b7f2a0-0000-5000-8000-6d6f62676170")

private class TimingLogger : ExecutionLogger {
    /** step name -> total duration ms (-1 = failed) */
    val stepDurations = LinkedHashMap<String, Long>()
    override fun onStepStarted(runId: UUID, stepId: UUID, stepName: String) = Unit
    override fun onStepCompleted(runId: UUID, stepId: UUID, stepName: String, durationMs: Long) {
        stepDurations[stepName] = durationMs
    }
    override fun onStepFailed(runId: UUID, stepId: UUID, stepName: String, reason: String) {
        stepDurations[stepName] = -1
    }
}

private data class RunResult(
    val runIndex: Int,
    val totalWallMs: Long,
    val phaseMs: Map<String, Long>,
    val stepTotalMs: Map<String, Long>,
    val scriptSelfTimeS: Map<String, Double>,
    val outputs: Map<String, Pair<Long, String>>, // relative path -> (bytes, sha256)
    val succeeded: Boolean
)

fun main(args: Array<String>) {
    val runs = args.firstOrNull()?.toIntOrNull() ?: 2
    val results = (1..runs).map { executeTimedRun(it) }

    val outFile = timedEvalResultsDir().resolve("mobgap-timing.txt")
    val report = buildString {
        results.forEach { appendLine(formatRun(it)) }
        appendLine(formatHashComparison(results))
    }
    println(report)
    outFile.appendText(report + "\n")
    println("Appended results to: ${outFile.absolutePath}")
}

private fun executeTimedRun(runIndex: Int): RunResult {
    val phaseMs = LinkedHashMap<String, Long>()
    val timingLogger = TimingLogger()
    val demoResultsDir = timedEvalDemoResultsDir()
    val runId = UUID.parse("00000000-0000-0000-0000-00000000e${runIndex.toString().padStart(3, '0')}")
    val wallStart = System.nanoTime()

    // Clean previous results
    if (demoResultsDir.exists()) demoResultsDir.deleteRecursively()
    demoResultsDir.mkdirs()

    // Author-time + plan-time phases
    val yaml = loadTimedWorkflowResource()
    val codec = WorkflowYamlCodec()
    val descriptor = timed(phaseMs, "decode-yaml") { codec.decodeOrThrow(yaml) }
    val definition = timed(phaseMs, "import") { WorkflowDescriptorImporter(EVAL_NAMESPACE).import(descriptor) }
    val plan = timed(phaseMs, "plan") { DefaultExecutionPlanner().plan(definition) }
    timed(phaseMs, "validate") { plan.validate() }
    timed(phaseMs, "stage-workspace") { setupWorkspace(demoResultsDir) }

    // Execute
    val artefactStore = FileSystemArtefactStore(demoResultsDir.toPath().resolve("artifacts"))
    val workspaceManager = DefaultWorkspaceManager(demoResultsDir.toPath())
    val executor = DefaultPlanExecutor(
        workspaceManager = workspaceManager,
        artefactStore = artefactStore,
        options = DefaultPlanExecutor.Options(executionLogger = timingLogger)
    )
    println("Run $runIndex: executing workflow (${plan.steps.size} steps)...")
    val report = timed(phaseMs, "execute-total") { executor.execute(plan, runId) }
    val succeeded = report.status.toString() == "SUCCEEDED"
    if (!succeeded) {
        println("Run $runIndex FAILED: ${report.status}")
        report.issues.forEach { println("   - ${it.message}") }
    }

    // Bundle
    timed(phaseMs, "bundle") { PackageBuilder.build(descriptor) }

    val totalWallMs = (System.nanoTime() - wallStart) / 1_000_000

    // Collect outputs + script self-times from the run directory.
    // Step stderr is captured in run-level logs at `<run>/logs/<stepUuid>-<timestamp>.log`,
    // so self-times are keyed by step UUID and mapped back to step names here.
    val runRoot = demoResultsDir.walkTopDown().firstOrNull { it.isDirectory && it.name.startsWith("run_") }
    val uuidToName = plan.steps.associate { it.metadata.id.toString() to it.metadata.name }
    val outputs = LinkedHashMap<String, Pair<Long, String>>()
    val scriptTimes = LinkedHashMap<String, Double>()
    runRoot?.walkTopDown()?.filter { it.isFile }?.sortedBy { it.path }?.forEach { f ->
        val rel = f.relativeTo(runRoot).invariantSeparatorsPath
        if (rel.contains("/outputs/")) {
            outputs[rel] = Pair(f.length(), sha256(f))
        }
        // Script self-time lines are written to the recorded step logs (stderr section)
        if (f.length() < 5_000_000 && f.name.endsWith(".log")) {
            Regex("EVAL_SCRIPT_SECONDS=([0-9.]+)").find(f.readText())?.let { m ->
                // Log filename starts with the step UUID; map it to the step name.
                val uuid = uuidToName.keys.firstOrNull { f.name.startsWith(it) }
                val key = uuid?.let { uuidToName[it] } ?: f.name
                scriptTimes[key] = m.groupValues[1].toDouble()
            }
        }
    }

    return RunResult(
        runIndex = runIndex,
        totalWallMs = totalWallMs,
        phaseMs = phaseMs,
        stepTotalMs = timingLogger.stepDurations,
        scriptSelfTimeS = scriptTimes,
        outputs = outputs,
        succeeded = succeeded
    )
}

private fun formatRun(r: RunResult): String = buildString {
    appendLine("=".repeat(70))
    appendLine("Mobgap timed run ${r.runIndex} - ${java.time.Instant.now()}")
    appendLine("Status: ${if (r.succeeded) "SUCCEEDED" else "FAILED"}")
    appendLine("Total wall clock: ${r.totalWallMs} ms")
    if (r.runIndex == 1) appendLine("(run 1 includes env setup + dataset download - use later runs for steady state)")
    appendLine()
    appendLine("Framework phases (ms):")
    r.phaseMs.forEach { (k, v) -> appendLine("  %-18s %8d".format(k, v)) }
    appendLine()
    appendLine("Per-step: total ms | script compute ms | framework+env overhead ms (Fig D):")
    var sumTotal = 0L
    var sumCompute = 0.0
    var sumOverhead = 0.0
    r.stepTotalMs.forEach { (name, ms) ->
        val selfMs = r.scriptSelfTimeS[name]?.times(1000)
        val overhead = selfMs?.let { ms - it }
        appendLine(
            "  %-40s total=%6d  compute=%s  overhead=%s".format(
                name, ms,
                selfMs?.let { "%6.0f".format(it) } ?: "   n/a",
                overhead?.let { "%5.0f".format(it) } ?: "  n/a"
            )
        )
        if (ms >= 0 && selfMs != null) {
            sumTotal += ms; sumCompute += selfMs; sumOverhead += (overhead ?: 0.0)
        }
    }
    if (sumCompute > 0) {
        appendLine()
        appendLine(
            "  TOTALS: step total=%d ms | compute=%.0f ms | overhead=%.0f ms (%.1f%% of total)".format(
                sumTotal, sumCompute, sumOverhead, 100.0 * sumOverhead / sumTotal
            )
        )
        val n = r.stepTotalMs.count { r.scriptSelfTimeS.containsKey(it.key) }
        if (n > 0) appendLine("  Mean per-step overhead: %.0f ms".format(sumOverhead / n))
        val frameworkPhases = r.phaseMs.filterKeys { it != "execute-total" }.values.sum()
        appendLine("  Framework phases (decode+import+plan+validate+stage+bundle): $frameworkPhases ms")
    }
    appendLine()
    appendLine("Outputs (bytes, sha256):")
    r.outputs.forEach { (rel, p) -> appendLine("  %-60s %10d  %s".format(rel, p.first, p.second)) }
}

private fun formatHashComparison(results: List<RunResult>): String {
    if (results.size < 2) return "Single run - no cross-run hash comparison."
    return buildString {
        appendLine("=".repeat(70))
        appendLine("Output determinism across ${results.size} runs (same machine):")
        val allPaths = results.flatMap { it.outputs.keys }.toSortedSet()
        var identical = 0
        var differing = 0
        allPaths.forEach { path ->
            val hashes = results.map { it.outputs[path]?.second ?: "<missing>" }.toSet()
            if (hashes.size == 1) identical++ else {
                differing++
                appendLine("  DIFFERS: $path -> $hashes")
            }
        }
        appendLine("  Identical: $identical / ${allPaths.size}, differing: $differing")
    }
}

private inline fun <T> timed(sink: MutableMap<String, Long>, name: String, block: () -> T): T {
    val start = System.nanoTime()
    val result = block()
    sink[name] = (System.nanoTime() - start) / 1_000_000
    return result
}

private fun sha256(f: File): String =
    MessageDigest.getInstance("SHA-256").digest(f.readBytes())
        .joinToString("") { "%02x".format(it) }

// --- Workspace setup (mirrors MobgapDemo) ---

private object TimedEvalAnchor

private fun loadTimedWorkflowResource(): String =
    TimedEvalAnchor::class.java.classLoader
        .getResource("workflows/mobgap-gait-analysis.yaml")
        ?.readText()
        ?: throw IllegalStateException("Workflow YAML not found")

private fun setupWorkspace(demoResultsDir: File) {
    val workflowsDir = demoResultsDir.resolve("resources/workflows").apply { mkdirs() }
    copyResource("workflows/mobgap-gait-analysis.yaml", workflowsDir.resolve("mobgap-gait-analysis.yaml"))
    val scriptsDir = demoResultsDir.resolve("resources/scripts/mobgap").apply { mkdirs() }
    listOf(
        "import_data.py", "gsd.py", "icd.py", "per_sec_params.py",
        "wba.py", "aggregate.py", "plot_wb_params.py", "plot_aggregated_dmos.py"
    ).forEach { copyResource("scripts/mobgap/$it", scriptsDir.resolve(it)) }
}

private fun copyResource(resourcePath: String, target: File) {
    val resource = TimedEvalAnchor::class.java.classLoader.getResource(resourcePath)
        ?: throw IllegalStateException("Resource not found: $resourcePath")
    Files.copy(resource.openStream(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
}

private fun timedEvalProjectRoot(): File {
    val classPath = TimedEvalAnchor::class.java.protectionDomain.codeSource.location.toURI().path
    return File(classPath).parentFile?.parentFile?.parentFile?.parentFile?.parentFile
        ?: throw IllegalStateException("Cannot determine project root")
}

private fun timedEvalResultsDir(): File =
    timedEvalProjectRoot().resolve("eval_results").apply { mkdirs() }

private fun timedEvalDemoResultsDir(): File =
    timedEvalProjectRoot().resolve("demo_results").resolve("mobgap_timed")
