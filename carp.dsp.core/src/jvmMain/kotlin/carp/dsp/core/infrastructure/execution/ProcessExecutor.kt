package carp.dsp.core.infrastructure.execution

import java.io.IOException

/**
 * Generic process executor for running shell commands.
 *
 * This class should eventually be moved to carp.analytics.core as it's generic infrastructure.
 * Location: dk.cachet.carp.analytics.infrastructure.execution.ProcessExecutor
 */
class ProcessExecutor
{
    /**
     * Executes a shell command with optional environment variables.
     *
     * @param command The command to execute
     * @param envVariables Environment variables to set
     * @return The stdout output from the command
     * @throws IllegalStateException if command execution fails
     */
    fun executeCommand(command: String, envVariables: Map<String, String> = emptyMap()): String {
        try {
            val processBuilder = if (System.getProperty("os.name").startsWith("Windows")) {
                ProcessBuilder("cmd.exe", "/c", command)
            } else {
                ProcessBuilder("sh", "-c", command)
            }

            // Add environment variables
            envVariables.forEach { (key, value) ->
                processBuilder.environment()[key] = value
            }

            val process = processBuilder
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                throw IllegalStateException(
                    "Command failed with exit code $exitCode\n" +
                    "Command: $command\n" +
                    "Output: $output"
                )
            }

            return output
        } catch (e: IOException) {
            throw IllegalStateException("Failed to execute command: ${e.message}", e)
        } catch (e: InterruptedException) {
            throw IllegalStateException("Command execution was interrupted: ${e.message}", e)
        }
    }

    /**
     * Executes a command without throwing on non-zero exit codes.
     * Returns both output and exit code.
     */
    fun executeCommandSafe(command: String, envVariables: Map<String, String> = emptyMap()): ExecutionResult {
        return try {
            val processBuilder = if (System.getProperty("os.name").startsWith("Windows")) {
                ProcessBuilder("cmd.exe", "/c", command)
            } else {
                ProcessBuilder("sh", "-c", command)
            }

            envVariables.forEach { (key, value) ->
                processBuilder.environment()[key] = value
            }

            val process = processBuilder
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            ExecutionResult(exitCode, output, null)
        } catch (e: Exception) {
            ExecutionResult(-1, "", e.message ?: "Unknown error")
        }
    }

    data class ExecutionResult(
        val exitCode: Int,
        val output: String,
        val error: String?
    )
}

