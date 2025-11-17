package carp.dsp.core.infrastructure.execution

import carp.dsp.core.application.process.PythonProcess
import dk.cachet.carp.analytics.domain.execution.ExecutionContext
import dk.cachet.carp.analytics.domain.execution.Executor
import java.io.IOException

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
class PythonExecutor : Executor<PythonProcess> {

    private val environmentSetupExecutor = EnvironmentSetupExecutor()

    /**
     * Sets up the environment for the Python process.
     * For conda environments, ensures the environment exists (creates it if missing).
     */
    override fun setup(process: PythonProcess, context: ExecutionContext) {
        println("Setting up Python process: ${process.name}")

        if (process.useCondaRun) {
            context.environment?.let { env ->
                println("Ensuring conda environment: ${env.name}")

                // Use the new ensureCondaEnvironment that automatically creates if missing
                val ensured = environmentSetupExecutor.ensureCondaEnvironment(
                    environment = env,
                    createIfMissing = true
                )

                if (!ensured) {
                    // This should rarely happen since createIfMissing = true
                    println("Available environments:")
                    environmentSetupExecutor.listCondaEnvironments().forEach { envName ->
                        println("  - $envName")
                    }
                    error("Cannot execute: Failed to ensure conda environment '${env.name}'")
                }

                println("✓ Conda environment '${env.name}' is ready")
            } ?: run {
                error("Execution context must specify an environment when using conda run")
            }
        } else {
            println("Direct Python execution mode (not using conda)")
        }

        println("Setup complete for: ${process.name}")
    }

    /**
     * Executes the Python process.
     * Handles stdin input, runs the command, and captures output.
     */
    override fun execute(process: PythonProcess, context: ExecutionContext) {
        val command = process.getFormattedCommand()
        val stdinData = process.getStdinBuffer()

        println("Executing Python process: ${process.name}")
        println("Command: $command")

        try {
            val processBuilder = createProcessBuilder(command, context)
            val proc = processBuilder.start()

            // Write stdin data if present
            if (stdinData.isNotEmpty()) {
                println("Writing ${stdinData.length} characters to stdin...")
                proc.outputStream.bufferedWriter().use { writer ->
                    writer.write(stdinData)
                    writer.flush()
                }
            }

            // Capture stdout and stderr
            val stdout = proc.inputStream.bufferedReader().readText()
            val stderr = proc.errorStream.bufferedReader().readText()

            // Wait for process to complete
            val exitCode = proc.waitFor()

            // Display output
            if (stdout.isNotEmpty()) {
                println("STDOUT:")
                println(stdout)
            }

            if (stderr.isNotEmpty()) {
                println("STDERR:")
                println(stderr)
            }

            if (exitCode != 0) {
                throw IllegalStateException(
                    "Python process failed with exit code $exitCode\n" +
                    "Command: $command\n" +
                    "STDERR: $stderr"
                )
            }

            println("✓ Python process completed successfully")

        } catch (e: IOException) {
            throw IllegalStateException("Failed to execute Python process: ${e.message}", e)
        } catch (e: InterruptedException) {
            throw IllegalStateException("Python process was interrupted: ${e.message}", e)
        }
    }

    /**
     * Cleanup after process execution.
     * Currently no cleanup needed, but could be extended for temp file cleanup.
     */
    override fun cleanup(process: PythonProcess, context: ExecutionContext) {
        println("Cleanup completed for Python process: ${process.name}")
    }

    /**
     * Creates a ProcessBuilder configured with the command and environment variables.
     */
    private fun createProcessBuilder(command: String, context: ExecutionContext): ProcessBuilder {
        // On Windows, use cmd.exe to execute the command
        val processBuilder = if (System.getProperty("os.name").startsWith("Windows")) {
            ProcessBuilder("cmd.exe", "/c", command)
        } else {
            ProcessBuilder("sh", "-c", command)
        }

        // Add environment variables from context
        context.envVariables.forEach { (key, value) ->
            processBuilder.environment()[key] = value
        }

        // Redirect error stream
        processBuilder.redirectErrorStream(false)

        return processBuilder
    }
}
