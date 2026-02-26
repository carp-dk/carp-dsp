package carp.dsp.core.infrastructure.runtime

import carp.dsp.core.application.execution.CommandPolicy
import carp.dsp.core.application.execution.RelativePath
import dk.cachet.carp.analytics.application.execution.RunPolicy
import dk.cachet.carp.analytics.application.plan.CommandSpec
import dk.cachet.carp.analytics.application.runtime.CommandResult
import dk.cachet.carp.analytics.application.runtime.CommandRunner
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Executes OS processes based on a structured [CommandSpec].
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

    override fun run(command: CommandSpec, policy: RunPolicy): CommandResult {
        policy as CommandPolicy

        val processBuilder = ProcessBuilder(listOf(command.executable) + command.args)

        val workspaceRoot = Files.createTempDirectory("carp-dsp-workspace-")
        policy.workingDirectory?.let { rel ->
            val wd = resolveUnderRoot(workspaceRoot, rel)
            Files.createDirectories(wd)
            processBuilder.directory(wd.toFile())
        }

        val start = System.nanoTime()
        val process = processBuilder.start()

        val stdout = StringBuilder()
        val stderr = StringBuilder()

        val stdoutCollector = collectStream(process.inputStream, stdout)
        val stderrCollector = collectStream(process.errorStream, stderr)

        val timedOut = try {
            val finished = if (policy.timeoutMs != null) {
                process.waitFor(policy.timeoutMs, TimeUnit.MILLISECONDS)
            } else {
                process.waitFor()
                true
            }

            if (!finished) {
                process.destroy()
                if (process.isAlive) process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS) // grace prevents collector deadlocks
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

    internal fun resolveUnderRoot(root: Path, rel: RelativePath): Path {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val resolved = normalizedRoot.resolve(rel.value).normalize()

        require(resolved.startsWith(normalizedRoot)) {
            "Resolved path escapes workspace root. root=$normalizedRoot rel=${rel.value} resolved=$resolved"
        }
        return resolved
    }
}
