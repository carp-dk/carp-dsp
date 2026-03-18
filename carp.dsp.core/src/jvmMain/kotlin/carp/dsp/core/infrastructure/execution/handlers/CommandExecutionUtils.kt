package carp.dsp.core.infrastructure.execution.handlers

import java.io.IOException
import java.nio.file.Path

internal fun executeCommand(
    cmd: List<String>,
    workingDir: Path? = null
): CommandResult {
    return try {
        val processBuilder = ProcessBuilder(cmd)
        if (workingDir != null) {
            processBuilder.directory(workingDir.toFile())
        }

        val process = processBuilder
            .redirectErrorStream(false)
            .start()

        val exitCode = process.waitFor()
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }

        CommandResult(exitCode, stdout, stderr)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        CommandResult(1, "", "Command execution interrupted")
    } catch (e: IOException) {
        CommandResult(1, "", e.message ?: "I/O error during command execution")
    } catch (e: IllegalArgumentException) {
        CommandResult(1, "", e.message ?: "Invalid command arguments")
    }
}

internal data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)
