package carp.dsp.demo.demos

import carp.dsp.core.infrastructure.execution.CommandStepRunner
import carp.dsp.core.infrastructure.execution.FileSystemArtefactRecorder
import carp.dsp.core.infrastructure.execution.FileSystemArtefactStore
import carp.dsp.core.infrastructure.execution.FileSystemStepLogRecorder
import carp.dsp.core.infrastructure.runtime.JvmCommandRunner
import dk.cachet.carp.analytics.application.execution.workspace.ExecutionWorkspace
import dk.cachet.carp.analytics.application.execution.workspace.WorkspaceManager
import dk.cachet.carp.analytics.application.plan.*
import dk.cachet.carp.analytics.domain.workflow.StepMetadata
import dk.cachet.carp.common.application.UUID
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

class StepExecutionDemo {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            run(args.toList())
        }

        fun run() {
            val defaultCommand = if (isWindows()) {
                shellCommand("echo demo execution from StepExecutionDemo")
            } else {
                shellCommand("echo 'demo execution from StepExecutionDemo'")
            }
            executeDemo(defaultCommand, "default command")
        }

        fun run(args: List<String>) {
            if (args.isEmpty()) {
                printUsage()
                run()
                return
            }

            val command = parseCommand(args)
            executeDemo(command, args.joinToString(" "))
        }

        @OptIn(ExperimentalPathApi::class)
        private fun executeDemo(command: CommandSpec, commandLabel: String) {
            val tmpDir = Files.createTempDirectory("step-execution-demo")
            try {
                val workspace = ExecutionWorkspace(UUID.randomUUID(), tmpDir.toString(), "StepExecutionDemo")
                val artefactStore = FileSystemArtefactStore(tmpDir.resolve("artifacts"))
                val runner = CommandStepRunner(
                    workspaceManager = DemoWorkspaceManager(tmpDir),
                    commandRunner = JvmCommandRunner(),
                    artefactStore = artefactStore,
                    options = CommandStepRunner.Options(
                        artefactRecorder = FileSystemArtefactRecorder(),
                        logRecorder = FileSystemStepLogRecorder()
                    )
                )

                val step = createDemoStep(command)
                val result = runner.run(step, workspace)

                println("------------------------------------------------------------")
                println("Step execution demo")
                println("Workspace:      $tmpDir")
                println("Command:        $commandLabel")
                println("Status:         ${result.status}")
                println("Started:        ${result.startedAt}")
                println("Finished:       ${result.finishedAt}")

                val outputs = result.outputs.orEmpty()
                println("Artifacts:      ${outputs.size}")
                outputs.forEach { artifact ->
                    println("  - ${artifact.location.value}")
                }

                val logRef = result.detail?.stdout
                if (logRef != null) {
                    val logPath = tmpDir.resolve(logRef.value)
                    println("Log ref:        ${logRef.value}")
                    if (logPath.exists()) {
                        val preview = logPath.readText().lineSequence().take(6).joinToString("\n")
                        println("Log preview:\n$preview")
                    }
                }
                println("------------------------------------------------------------")
            } finally {
                tmpDir.deleteRecursively()
            }
        }

        private fun createDemoStep(command: CommandSpec): PlannedStep {
            // This demo executes arbitrary commands; outputs are not pre-declared.
            return PlannedStep(
                metadata = StepMetadata(
                    id = UUID.randomUUID(),
                    name = "demo-step"
                ),
                process = command,
                bindings = ResolvedBindings(
                    outputs = emptyMap()
                ),
                environmentRef = null
            )
        }

        private fun parseCommand(args: List<String>): CommandSpec {
            return if (args.size == 1) {
                shellCommand(args.first())
            } else {
                CommandSpec(
                    executable = args.first(),
                    args = args.drop(1).map { ExpandedArg.Literal(it) }
                )
            }
        }

        private fun shellCommand(script: String): CommandSpec {
            return if (isWindows()) {
                CommandSpec(
                    executable = "cmd",
                    args = listOf(
                        ExpandedArg.Literal("/c"),
                        ExpandedArg.Literal(script)
                    )
                )
            } else {
                CommandSpec(
                    executable = "sh",
                    args = listOf(
                        ExpandedArg.Literal("-c"),
                        ExpandedArg.Literal(script)
                    )
                )
            }
        }

        private fun printUsage() {
            println("Usage:")
            println("  StepExecutionDemo <command>")
            println("  StepExecutionDemo <executable> <arg1> <arg2> ...")
            println("No args provided, running a default echo command.")
        }

        private fun isWindows(): Boolean =
            System.getProperty("os.name").lowercase().contains("win")
    }
}

private class DemoWorkspaceManager(
    private val root: Path
) : WorkspaceManager {
    override fun create(plan: ExecutionPlan, runId: UUID): ExecutionWorkspace =
        ExecutionWorkspace(runId = runId, executionRoot = root.toString(), "StepExecutionDemo")

    override fun prepareStepDirectories(workspace: ExecutionWorkspace, stepId: UUID) {
        root.resolve("steps").resolve(stepId.toString()).resolve("work").createDirectories()
        root.resolve("steps").resolve(stepId.toString()).resolve("inputs").createDirectories()
        root.resolve("steps").resolve(stepId.toString()).resolve("outputs").createDirectories()
        root.resolve("steps").resolve(stepId.toString()).resolve("logs").createDirectories()
        root.resolve("logs").createDirectories()
    }

    override fun resolveStepWorkingDir(workspace: ExecutionWorkspace, stepId: UUID): String {
        val dir = root.resolve("steps").resolve(stepId.toString()).resolve("work")
        dir.createDirectories()
        return dir.toString()
    }
}