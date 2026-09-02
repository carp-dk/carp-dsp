package carp.dsp.core.infrastructure.execution.workspace

import dk.cachet.carp.analytics.application.execution.workspace.ExecutionWorkspace
import dk.cachet.carp.common.application.UUID
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * Materialises a [WorkspaceProvisioning] into a run's [ExecutionWorkspace].
 *
 * This is the staging concern, kept off the executor: it writes each resolved
 * library step's impl content and copies its declared inputs into the run
 * workspace before any step runs. Commands run with the execution root as their
 * working directory, so files are staged there at the path each command
 * references.
 *
 * Because steps share that root, two steps that stage a different file at the same
 * relative path would collide silently. That is detected here and fails fast,
 * naming both steps, rather than letting one step's bytes shadow another's at run
 * time. Steps that reuse the same library step stage identical bytes at the same
 * path, which is idempotent and allowed.
 *
 * A path that would escape the workspace root is refused. A declared input whose
 * source file is missing is logged rather than fatal - the step surfaces the
 * failure when it cannot read it.
 */
class WorkspaceProvisioner
{
    private val logger = KotlinLogging.logger {}

    /** Stage every file in [provisioning] into the run workspace. */
    fun provision( workspace: ExecutionWorkspace, provisioning: WorkspaceProvisioning )
    {
        if ( provisioning.isEmpty ) return
        val root = Path.of( workspace.executionRoot ).normalize()
        val staged = HashMap<String, Pair<UUID, FileSource>>()

        provisioning.byStep.forEach { ( stepId, files ) ->
            files.forEach { ( relativePath, source ) ->
                val clash = staged[relativePath]
                if ( clash != null && clash.second != source )
                {
                    throw IllegalStateException(
                        "Steps ${clash.first} and $stepId both stage '$relativePath' with different " +
                            "content. Give one of them a distinct path."
                    )
                }
                staged[relativePath] = stepId to source

                val target = root.resolve( relativePath ).normalize()
                require( target.startsWith( root ) ) {
                    "staged path '$relativePath' escapes the workspace root"
                }
                target.parent?.createDirectories()
                write( target, source )
            }
        }
    }

    private fun write( target: Path, source: FileSource ) = when ( source )
    {
        is FileSource.Content -> target.writeText( source.text )
        is FileSource.Copy ->
            if ( source.from.exists() )
                Files.copy( source.from, target, StandardCopyOption.REPLACE_EXISTING ).let {}
            else
                logger.warn { "Declared input '${source.from}' does not exist; step may fail" }
    }
}
