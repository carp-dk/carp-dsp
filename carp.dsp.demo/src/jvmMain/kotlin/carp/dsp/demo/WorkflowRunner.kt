package carp.dsp.demo

import carp.dsp.core.application.authoring.mapper.WorkflowDescriptorImporter
import carp.dsp.core.application.execution.ExecutionLogger
import carp.dsp.core.application.plan.DefaultExecutionPlanner
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

    // 1. Load and validate YAML
    val yamlText = try {
        Path(workflowPath).readText()
    } catch (e: Exception) {
        System.err.println("Failed to read workflow file '$workflowPath': ${e.message}")
        exitProcess(1)
    }

    val descriptor = try {
        WorkflowYamlCodec().decodeOrThrow(yamlText)
    } catch (e: Exception) {
        System.err.println("Failed to parse workflow YAML: ${e.message}")
        exitProcess(1)
    }

    println("Workflow: ${descriptor.metadata.name}")

    // 2. Import and plan
    val definition = WorkflowDescriptorImporter().import(descriptor)
    val plan = DefaultExecutionPlanner().plan(definition)
    plan.validate()
    println("Plan: ${plan.steps.size} step(s)")
    println()

    // 3. Set up workspace
    val workspaceDir = Path(workspacePath)
    workspaceDir.createDirectories()

    val artefactStore = FileSystemArtefactStore(workspaceDir.resolve("artifacts"))
    val workspaceManager = DefaultWorkspaceManager(workspaceDir)

    // 4. Execute with console progress logger
    val consoleLogger = ConsoleExecutionLogger()
    val executor = DefaultPlanExecutor(
        workspaceManager = workspaceManager,
        artefactStore = artefactStore,
        options = DefaultPlanExecutor.Options(executionLogger = consoleLogger)
    )

    val runId = UUID.randomUUID()
    val report = executor.execute(plan, runId)

    // 5. Print final status
    println()
    val failedStep = report.stepResults.firstOrNull {
        it.status.toString() == "FAILED"
    }

    if (report.status.toString() == "SUCCEEDED") {
        println("Workflow complete: SUCCESS")
        println("Outputs written to: $workspaceDir")
        exitProcess(0)
    } else {
        val failedName = failedStep?.stepMetadata?.name ?: "unknown"
        println("FAILED at step: $failedName")
        report.issues.forEach { println("  - ${it.message}") }
        exitProcess(1)
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