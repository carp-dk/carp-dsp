package carp.dsp.core.infrastructure.runtime

import carp.dsp.core.application.execution.CommandPolicy
import carp.dsp.core.application.execution.RelativePath
import dk.cachet.carp.analytics.application.execution.RunPolicy
import dk.cachet.carp.analytics.application.plan.CommandSpec
import dk.cachet.carp.analytics.application.plan.ExpandedArg
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
 *
 * Working directory resolution:
 * - Use [run] (interface method) when no workspace root is available; the process inherits the JVM's CWD.
 * - Use [run] with an explicit workspaceRoot to resolve [CommandPolicy.workingDirectory] safely
 *   against a real on-disk workspace root.
 */
class JvmCommandRunner(
    private val timeoutExitCode: Int = -1,
    private val charset: Charset = StandardCharsets.UTF_8
) : CommandRunner
{
    /**
     * Runs [command] using the JVM's current working directory as the process root.
     * [CommandPolicy.workingDirectory] is ignored — prefer [run] with an explicit workspaceRoot.
     */
    override fun run( command: CommandSpec, policy: RunPolicy ): CommandResult =
        execute( command, policy, workspaceRoot = null )

    /**
     * Runs [command] with [CommandPolicy.workingDirectory] resolved against [workspaceRoot].
     *
     * @param workspaceRoot Absolute path to the execution workspace root. The policy's relative
     *                      working directory is resolved safely under this root.
     */
    fun run( command: CommandSpec, policy: RunPolicy, workspaceRoot: Path ): CommandResult =
        execute( command, policy, workspaceRoot )

    // Core implementation

    private fun execute( command: CommandSpec, policy: RunPolicy, workspaceRoot: Path? ): CommandResult
    {
        val cmdPolicy = normalizePolicy( policy )
        val processBuilder = buildProcess( command, cmdPolicy, workspaceRoot )

        val start = System.nanoTime()
        val process = processBuilder.start()
        val ( stdout, stderr, collectors ) = startStreamCollectors( process )

        val timedOut = waitForProcess( process, cmdPolicy )
        collectors.first.join()
        collectors.second.join()

        val durationMs = TimeUnit.NANOSECONDS.toMillis( System.nanoTime() - start )
        val exitCode = if ( timedOut ) timeoutExitCode else process.exitValue()

        return CommandResult(
            exitCode = exitCode,
            stdout = stdout.toString(),
            stderr = stderr.toString(),
            durationMs = durationMs,
            timedOut = timedOut
        )
    }

    private fun normalizePolicy( policy: RunPolicy ): CommandPolicy =
        policy as? CommandPolicy
            ?: CommandPolicy(
                timeoutMs = policy.timeoutMs,
                stopOnFailure = policy.stopOnFailure,
                failOnWarnings = policy.failOnWarnings,
                maxAttempts = policy.maxAttempts,
                workingDirectory = null
            )

    private fun buildProcess(
        command: CommandSpec,
        policy: CommandPolicy,
        workspaceRoot: Path?
    ): ProcessBuilder
    {
        val resolvedArgs = command.args.map { arg ->
            when ( arg )
            {
                is ExpandedArg.Literal -> arg.value
                is ExpandedArg.DataReference -> arg.id.toString()
                is ExpandedArg.PathSubstitution -> arg.template.replace( "()", arg.id.toString() )
                is ExpandedArg.EnvironmentVariable -> arg.template.replace( "()", System.getenv( arg.name ) ?: "" )
            }
        }

        val builder = ProcessBuilder( listOf( command.executable ) + resolvedArgs )

        if ( workspaceRoot != null )
        {
            val wd = policy.workingDirectory
                ?.let { rel -> resolveUnderRoot( workspaceRoot, rel ) }
                ?: workspaceRoot
            Files.createDirectories( wd )
            builder.directory( wd.toFile() )
        }

        return builder
    }

    private fun startStreamCollectors( process: Process ): Triple<StringBuilder, StringBuilder, Pair<Thread, Thread>>
    {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutCollector = collectStream( process.inputStream, stdout )
        val stderrCollector = collectStream( process.errorStream, stderr )
        return Triple( stdout, stderr, stdoutCollector to stderrCollector )
    }

    private fun waitForProcess( process: Process, policy: CommandPolicy ): Boolean
    {
        return try
        {
            val finished = if ( policy.timeoutMs != null )
            {
                process.waitFor( policy.timeoutMs, TimeUnit.MILLISECONDS )
            }
            else
            {
                process.waitFor()
                true
            }

            if ( !finished )
            {
                process.destroy()
                if ( process.isAlive ) process.destroyForcibly()
                process.waitFor( 2, TimeUnit.SECONDS )
            }
            !finished
        }
        catch ( ex: InterruptedException )
        {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw ex
        }
    }

    private fun collectStream( stream: InputStream, target: StringBuilder ): Thread =
        thread( name = "JvmCommandRunner-collect-stream", start = true ) {
            stream.bufferedReader( charset ).use { reader ->
                target.append( reader.readText() )
            }
        }

    internal fun resolveUnderRoot( root: Path, rel: RelativePath ): Path
    {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val resolved = normalizedRoot.resolve( rel.value ).normalize()

        require( resolved.startsWith( normalizedRoot ) ) {
            "Resolved path escapes workspace root. root=$normalizedRoot rel=${rel.value} resolved=$resolved"
        }
        return resolved
    }
}
