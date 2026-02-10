package carp.dsp.core.infrastructure.execution

import carp.dsp.core.application.process.PythonProcess
import carp.dsp.core.infrastructure.runtime.command.CondaCommands
import carp.dsp.core.infrastructure.runtime.JvmCommandRunner
import dk.cachet.carp.analytics.application.runtime.Command
import dk.cachet.carp.analytics.application.runtime.CommandRunner
import dk.cachet.carp.analytics.domain.execution.Executor
import dk.cachet.carp.analytics.domain.workflow.Step
import java.nio.charset.StandardCharsets

/**
 * Executor for Python processes. Handles the execution of Python scripts within a specified environment.
 *
 * This executor:
 * - Runs Python scripts using conda environments or direct Python execution
 * - Handles stdin data (in-memory inputs)
 * - Captures stdout/stderr
 * - Manages process lifecycle
 * - Validates and sets up conda environments
 */
class PythonStepExecutor : Executor {

    private val environmentSetupExecutor = EnvironmentSetupExecutor()
    private val commandRunner: CommandRunner = JvmCommandRunner()
    private val condaCommands = CondaCommands()

    override fun setup(step: Step) {
        val process = step.process as? PythonProcess
            ?: error("PythonStepExecutor can only handle PythonProcess")

        println("Setting up Python process: ${process.name}")

        if (!process.useCondaRun) {
            println("Direct Python execution mode (not using conda)")
            println("Setup complete for: ${process.name}")
            return
        }

        val env = step.executionContext.environment
            ?: error("Execution context must specify an environment when using conda run")

        println("Ensuring conda environment: ${env.name}")
        val ensured = environmentSetupExecutor.ensureCondaEnvironment(env, createIfMissing = true)
        if (!ensured) listAvailableEnvironmentsAndFail(env.name)
        println("✓ Conda environment '${env.name}' is ready")
        println("Setup complete for: ${process.name}")
    }

    override fun execute(step: Step) {
        val process = step.process as? PythonProcess
            ?: error("PythonStepExecutor can only handle PythonProcess")

        val command = buildCommand(process, step)

        println("Executing Python process: ${process.name}")
        println("Command: ${command.exe} ${command.args.joinToString(" ")}")

        val result = try {
            commandRunner.run(command)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to execute Python process: ${e.message}", e)
        }

        if (result.stdout.isNotEmpty()) {
            println("STDOUT:\n${result.stdout}")
        }
        if (result.stderr.isNotEmpty()) {
            println("STDERR:\n${result.stderr}")
        }

        if (result.timedOut) {
            error("Python process timed out after ${result.durationMs} ms\nCommand: ${command.exe}")
        }
        if (result.exitCode != 0) {
            error(
                "Python process failed with exit code ${result.exitCode}\n" +
                    "Command: ${command.exe} ${command.args.joinToString(" ")}\n" +
                    "STDERR: ${result.stderr}"
            )
        }

        println("✓ Python process completed successfully")
    }

    private fun buildCommand(process: PythonProcess, step: Step): Command {
        val args = extractArguments(process)
        val stdinBytes = process.getStdinBuffer().toByteArray(StandardCharsets.UTF_8)

        return if (process.useCondaRun) {
            val envName = step.executionContext.environment?.name
                ?: error("Environment must be specified when using conda run")

            condaCommands.runInEnv(
                envName = envName,
                exe = process.pythonExecutable,
                args = listOf(process.scriptPath) + args,
                cwd = null,
                envVars = step.executionContext.envVariables,
                stdin = stdinBytes,
                timeoutMs = null
            )
        } else {
            Command(
                exe = process.pythonExecutable,
                args = listOf(process.scriptPath) + args,
                cwd = null,
                env = step.executionContext.envVariables,
                stdin = stdinBytes,
                timeoutMs = null
            )
        }
    }

    private fun extractArguments(process: PythonProcess): List<String> {
        val args = (process.getArguments() as? Map<*, *>)?.get("arguments")
        return (args as? List<*>)?.map { it.toString() } ?: process.arguments
    }

    private fun listAvailableEnvironmentsAndFail(envName: String): Nothing {
        println("Available environments:")
        environmentSetupExecutor.listCondaEnvironments().forEach { name ->
            println("  - $name")
        }
        error("Cannot execute: Failed to ensure conda environment '$envName'")
    }
}
