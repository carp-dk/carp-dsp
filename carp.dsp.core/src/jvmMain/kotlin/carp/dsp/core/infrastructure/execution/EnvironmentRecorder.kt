package carp.dsp.core.infrastructure.execution

import carp.dsp.core.infrastructure.execution.handlers.EnvironmentStore
import carp.dsp.core.infrastructure.runtime.JvmCommandRunner
import dk.cachet.carp.analytics.application.execution.DefaultRunPolicy
import dk.cachet.carp.analytics.application.plan.CommandSpec
import dk.cachet.carp.analytics.application.plan.CondaEnvironmentRef
import dk.cachet.carp.analytics.application.plan.EnvironmentRef
import dk.cachet.carp.analytics.application.plan.ExpandedArg
import dk.cachet.carp.analytics.application.plan.PixiEnvironmentRef
import dk.cachet.carp.analytics.application.plan.REnvironmentRef
import dk.cachet.carp.analytics.application.plan.SystemEnvironmentRef
import dk.cachet.carp.analytics.application.runtime.CommandRunner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Records what an environment actually contained when a run used it.
 *
 * An environment is only as pinned as its spec, so the run keeps what was installed:
 * the `pixi.lock` of a pixi environment, `conda list --explicit` of a conda one, the
 * `renv.lock` (or the declared packages' versions) of an R one. A system environment
 * pins nothing; its record says so and names the OS.
 *
 * Each record is written to `environments/<kind>-<name>.txt` in the run root.
 */
internal class EnvironmentRecorder( private val runner: CommandRunner = JvmCommandRunner() )
{
    /** What was recorded for one environment. [content] is empty when nothing could be read. */
    data class Record( val kind: String, val name: String, val source: String, val content: String )

    /** Returns the record of [ref]; never throws, an unreadable source gives an empty [Record.content]. */
    fun record( ref: EnvironmentRef ): Record = when ( ref )
    {
        is PixiEnvironmentRef -> Record( "pixi", ref.name, "pixi.lock", pixiLock( ref ) )
        is CondaEnvironmentRef -> Record(
            "conda",
            ref.name,
            "conda list --explicit",
            run( "conda", "list", "-n", ref.name, "--explicit" )
        )
        is REnvironmentRef -> rRecord( ref )
        is SystemEnvironmentRef -> Record( "system", ref.id, "none (nothing pinned)", "os: ${osName()}\n" )
        else -> Record( ref::class.simpleName.orEmpty(), ref.id, "none (unknown kind)", "" )
    }

    /** Writes [record] under `environments/` in [executionRoot] and returns the file. */
    fun write( executionRoot: Path, record: Record ): Path
    {
        val dir = executionRoot.resolve( DIRECTORY ).also { Files.createDirectories( it ) }
        val file = dir.resolve( "${record.kind}-${slug( record.name )}.txt" )
        file.writeText(
            buildString {
                appendLine( "kind: ${record.kind}" )
                appendLine( "name: ${record.name}" )
                appendLine( "recorded from: ${record.source}" )
                appendLine( "---" )
                append( record.content )
            }
        )
        return file
    }

    private fun pixiLock( ref: PixiEnvironmentRef ): String =
        EnvironmentStore.resolve( ref )?.directory?.resolve( "pixi.lock" )
            ?.takeIf { it.exists() }?.readText().orEmpty()

    private fun rRecord( ref: REnvironmentRef ): Record
    {
        ref.renvLockFile?.let { Path.of( it ) }?.takeIf { it.exists() }?.let { lock ->
            return Record( "r", ref.name, "renv.lock", lock.readText() )
        }
        // No lock: the R version and the version of each declared package, as installed.
        val script = "cat(R.version.string, '\\n'); " +
            ref.rPackages.joinToString( "; " ) { p ->
                val name = p.substringBefore( "@" ).substringBefore( "=" ).trim()
                "cat('$name', tryCatch(as.character(packageVersion('$name')), " +
                    "error=function(e) 'not installed'), '\\n')"
            }
        return Record(
            "r",
            ref.name,
            "R.version and packageVersion() (no renv.lock)",
            run( "Rscript", "-e", script )
        )
    }

    /** Output of a command, or empty when it cannot run. */
    @Suppress( "TooGenericExceptionCaught" )
    private fun run( executable: String, vararg args: String ): String =
        try
        {
            val spec = CommandSpec( executable, args.map { ExpandedArg.Literal( it ) } )
            val result = runner.run( spec, DefaultRunPolicy() )
            if ( result.exitCode == 0 ) result.stdout else ""
        }
        catch ( _: Exception )
        {
            // The tool is missing or failed; an empty record says so, and the run goes on.
            ""
        }

    private fun osName(): String =
        "${System.getProperty( "os.name" )} ${System.getProperty( "os.version" )} ${System.getProperty( "os.arch" )}"

    private fun slug( name: String ): String = name.lowercase().replace( Regex( "[^a-z0-9]+" ), "-" ).trim( '-' )

    companion object
    {
        /** Folder in the run root that holds the environment records. */
        const val DIRECTORY = "environments"
    }
}
