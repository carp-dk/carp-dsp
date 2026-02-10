package carp.dsp.core.infrastructure.runtime

import dk.cachet.carp.analytics.application.runtime.Command
import dk.cachet.carp.analytics.application.runtime.CommandRunner
import dk.cachet.carp.analytics.application.runtime.CommandResult
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Executes OS processes based on a structured [Command].
 *
 * Failure policy: non-zero exit codes are returned without throwing. Fatal issues such as
 * failure to start the process or invalid working directories surface as thrown exceptions.
 * When a timeout is provided and exceeded, the process is destroyed and a [CommandResult]
 * with [CommandResult.timedOut] set to `true` and [CommandResult.exitCode] set to [timeoutExitCode] is returned.
 */
class JvmCommandRunner(
    private val timeoutExitCode: Int = -1,
    private val charset: Charset = StandardCharsets.UTF_8
) : CommandRunner {

    override fun run(command: Command): CommandResult {
        val processBuilder = ProcessBuilder(listOf(command.exe) + command.args)
        command.cwd?.let { cwd ->
            val dir = File(cwd)
            require(dir.exists() && dir.isDirectory) { "Working directory does not exist or is not a directory: $cwd" }
            processBuilder.directory(dir)
        }
        processBuilder.environment().putAll(command.env)

        val start = System.nanoTime()
        val process = try {
            processBuilder.start()
        } catch (ex: IOException) {
            // Starting the process failed; propagate as a fatal error.
            throw ex
        }

        val stdout = StringBuilder()
        val stderr = StringBuilder()

        val stdoutCollector = collectStream(process.inputStream, stdout)
        val stderrCollector = collectStream(process.errorStream, stderr)

        writeInput(process, command.stdin)

        val timedOut = try {
            val finished = command.timeoutMs?.let { process.waitFor(it, TimeUnit.MILLISECONDS) }
                ?: run { process.waitFor(); true }

            if (!finished) {
                process.destroy()
                if (process.isAlive) process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS) // grace to prevents stdX join deadlocks
            }
            !finished
        } catch (ex: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw ex
        }

        stdoutCollector.join()
        stderrCollector.join()

        val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        val exitCode = if (timedOut) timeoutExitCode else process.exitValue()

        return CommandResult(
            exitCode = exitCode,
            stdout = stdout.toString(),
            stderr = stderr.toString(),
            durationMs = durationMs,
            timedOut = timedOut
        )
    }

    private fun collectStream(stream: InputStream, target: StringBuilder): Thread =
        thread(name = "JvmCommandRunner-collect-stream", start = true) {
            stream.bufferedReader(charset).use { reader ->
                target.append(reader.readText())
            }
        }

    private fun writeInput(process: Process, input: ByteArray?) {
        if (input == null) {
            process.outputStream.close()
            return
        }

        process.outputStream.buffered().use { out ->
            out.write(input)
            out.flush()
        }
    }
}