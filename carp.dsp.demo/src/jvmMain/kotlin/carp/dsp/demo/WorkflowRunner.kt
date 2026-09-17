package carp.dsp.demo

import carp.dsp.core.application.execution.ExecutionLogger
import carp.dsp.core.application.run.WorkflowExecutor
import carp.dsp.core.application.run.WorkflowSource
import carp.dsp.steps.ClasspathStepLibrary
import dk.cachet.carp.analytics.application.execution.ExecutionStatus
import dk.cachet.carp.common.application.UUID
import kotlin.io.path.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val parsed = parseArgs(args)
    if (parsed == null) {
        System.err.println("Usage: run-workflow --workflow <path/to/workflow.yaml> [--workspace <dir>]")
        exitProcess(1)
    }
    val (workflowPath, workspacePath) = parsed
    exitProcess(runWorkflow(workflowPath, workspacePath))
}

/**
 * Runs a workflow YAML end to end and prints progress, returning a process exit
 * code (0 success, 1 failure).
 *
 * Argument parsing and console output are all this adds - the life cycle itself
 * is [WorkflowExecutor], which the demos and any service use too.
 */
fun runWorkflow(workflowPath: String, workspacePath: String): Int {
    val executor = WorkflowExecutor.filesystem(
        stepLibrary = ClasspathStepLibrary(),
        workspaceRoot = Path(workspacePath),
        options = WorkflowExecutor.Options(executionLogger = ConsoleExecutionLogger()),
    )

    // Read, resolve `uses:` references, import and plan. Writes/updates steps.lock
    // beside the workflow; a no-op for an all-inline workflow.
    val prepared = try {
        executor.prepare(WorkflowSource.of(workflowPath))
    } catch (e: Exception) {
        System.err.println("Failed to prepare workflow '$workflowPath': ${e.message}")
        return 1
    }

    println("Workflow: ${prepared.descriptor.metadata.name}")
    println("Plan: ${prepared.plan.steps.size} step(s)")

    // Structural checks the executor does not make: a plan with no steps, or with
    // no name, is a bad plan rather than a failed run, and should say so here.
    try {
        prepared.plan.validate()
    } catch (e: IllegalArgumentException) {
        System.err.println("Invalid plan: ${e.message}")
        return 1
    }

    if (!prepared.plan.isRunnable()) {
        System.err.println("Plan has errors and will not be run:")
        prepared.plan.issues.forEach { System.err.println("  - ${it.message}") }
        return 1
    }

    println()

    val report = executor.run(prepared, UUID.randomUUID())

    println()
    return if (report.status == ExecutionStatus.SUCCEEDED) {
        println("Workflow complete: SUCCESS")
        println("Outputs written to: ${Path(workspacePath).toAbsolutePath()}")
        0
    } else {
        val failedName = report.stepResults
            .firstOrNull { it.status == ExecutionStatus.FAILED }
            ?.stepMetadata?.name ?: "unknown"
        println("FAILED at step: $failedName")
        report.issues.forEach { println("  - ${it.message}") }
        1
    }
}

private data class RunnerArgs(val workflowPath: String, val workspacePath: String)

private fun parseArgs(args: Array<String>): RunnerArgs? {
    var workflow: String? = null
    var workspace = "./dsp-output"

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--workflow" -> { workflow = args.getOrNull(++i); i++ }
            "--workspace" -> { workspace = args.getOrNull(++i) ?: workspace; i++ }
            else -> i++
        }
    }

    return if (workflow != null) RunnerArgs(workflow, workspace) else null
}

private class ConsoleExecutionLogger : ExecutionLogger {
    override fun onStepStarted(runId: UUID, stepId: UUID, stepName: String) {
        print("Running step: $stepName... ")
    }

    override fun onStepCompleted(runId: UUID, stepId: UUID, stepName: String, durationMs: Long) {
        println("SUCCESS (${durationMs}ms)")
    }

    override fun onStepFailed(runId: UUID, stepId: UUID, stepName: String, reason: String) {
        println("FAILED")
        println("  Reason: $reason")
    }
}
