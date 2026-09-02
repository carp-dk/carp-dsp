package carp.dsp.demo

import carp.dsp.core.application.execution.ExecutionLogger
import java.io.File
import carp.dsp.core.infrastructure.execution.DefaultPlanExecutor
import carp.dsp.core.infrastructure.execution.FileSystemArtefactStore
import carp.dsp.core.infrastructure.execution.workspace.DefaultWorkspaceManager
import carp.dsp.core.infrastructure.serialization.WorkflowYamlCodec
import dk.cachet.carp.common.application.UUID
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
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
 * Runs a workflow YAML end to end: parse, resolve references, import, plan,
 * then execute - staging library impl files and provisioning declared file inputs
 * into the run workspace. Prints progress and returns a process exit code
 * (0 success, 1 failure). Shared by the CLI entry point and the library demo.
 */
fun runWorkflow(workflowPath: String, workspacePath: String): Int {
    // 1. Load and validate YAML
    val yamlText = try {
        Path(workflowPath).readText()
    } catch (e: Exception) {
        System.err.println("Failed to read workflow file '$workflowPath': ${e.message}")
        return 1
    }

    val descriptor = try {
        WorkflowYamlCodec().decodeOrThrow(yamlText)
    } catch (e: Exception) {
        System.err.println("Failed to parse workflow YAML: ${e.message}")
        return 1
    }

    println("Workflow: ${descriptor.metadata.name}")

    // 2. Resolve `uses:` references, import, plan, and collect what the run needs
    // staged. Writes/updates steps.lock beside the workflow; a no-op for an
    // all-inline workflow.
    val prepared = try {
        WorkflowPreparation.prepare(File(workflowPath), descriptor)
    } catch (e: Exception) {
        System.err.println("Failed to prepare workflow: ${e.message}")
        return 1
    }
    val plan = prepared.plan
    plan.validate()
    println("Plan: ${plan.steps.size} step(s)")
    println()

    // 3. Set up workspace (absolute - the workspace manager rejects relative roots)
    val workspaceDir = Path(workspacePath).toAbsolutePath()
    workspaceDir.createDirectories()

    val artefactStore = FileSystemArtefactStore(workspaceDir.resolve("artifacts"))
    val workspaceManager = DefaultWorkspaceManager(workspaceDir)

    // 4. Execute with console progress logger.
    val consoleLogger = ConsoleExecutionLogger()
    val executor = DefaultPlanExecutor(
        workspaceManager = workspaceManager,
        artefactStore = artefactStore,
        options = DefaultPlanExecutor.Options(executionLogger = consoleLogger)
    )

    val runId = UUID.randomUUID()
    val report = executor.run(plan, runId, prepared.provisioning)

    // 5. Print final status
    println()
    val failedStep = report.stepResults.firstOrNull {
        it.status.toString() == "FAILED"
    }

    return if (report.status.toString() == "SUCCEEDED") {
        println("Workflow complete: SUCCESS")
        println("Outputs written to: $workspaceDir")
        0
    } else {
        val failedName = failedStep?.stepMetadata?.name ?: "unknown"
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