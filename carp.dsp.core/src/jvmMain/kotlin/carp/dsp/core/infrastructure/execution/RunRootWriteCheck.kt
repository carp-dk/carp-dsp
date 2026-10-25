package carp.dsp.core.infrastructure.execution

import dk.cachet.carp.analytics.application.execution.ExecutionIssue
import dk.cachet.carp.analytics.application.execution.ExecutionIssueKind
import dk.cachet.carp.analytics.domain.workflow.StepMetadata
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo

/**
 * Finds files a step created or changed in the run root outside its own step folder.
 *
 * Steps share the run root as their working directory, so nothing stops a step writing
 * next to another step's files. This does not prevent such writes; it reports them, so a
 * step that leaks state into the run is visible in the run record.
 *
 * Not reported: the step's own folder, the runtime's `logs/` and `environments/` folders, and Python bytecode
 * caches (`__pycache__`, `*.pyc`) that the interpreter writes next to a staged script.
 */
internal object RunRootWriteCheck
{
    private const val LIST_LIMIT = 20

    /** Size and modification time of one file; a change in either counts as a write. */
    data class Stamp( val size: Long, val modifiedMs: Long )

    /** Returns every file under [executionRoot] that a write there could touch, keyed by relative path. */
    fun snapshot( executionRoot: Path, ownStepDir: String ): Map<String, Stamp>
    {
        if ( !Files.isDirectory( executionRoot ) ) return emptyMap()
        val own = ownStepDir.trimEnd( '/' ) + "/"
        return Files.walk( executionRoot ).use { paths ->
            paths.filter { it.isRegularFile() }
                .map { it to it.relativeTo( executionRoot ).toString().replace( '\\', '/' ) }
                .filter { ( _, rel ) -> !rel.startsWith( own ) && !isRuntimeFile( rel ) }
                .toList()
                .associate { ( file, rel ) -> rel to stampOf( file ) }
        }
    }

    /** Returns the relative paths created or changed between [before] and [after], sorted. */
    fun writes( before: Map<String, Stamp>, after: Map<String, Stamp> ): List<String> =
        after.filter { ( path, stamp ) -> before[ path ] != stamp }.keys.sorted()

    /** Returns an [ExecutionIssueKind.UNEXPECTED_OUTPUT] warning naming [files], or null when there are none. */
    fun issue( step: StepMetadata, files: List<String> ): ExecutionIssue?
    {
        if ( files.isEmpty() ) return null
        val listed = files.take( LIST_LIMIT )
        val more = if ( files.size > listed.size ) " (+${files.size - listed.size} more)" else ""
        return ExecutionIssue(
            stepMetadata = step,
            kind = ExecutionIssueKind.UNEXPECTED_OUTPUT,
            message = "Step '${step.name}' wrote outside its own folder: ${listed.joinToString( ", " )}$more"
        )
    }

    private fun stampOf( file: Path ) = Stamp( Files.size( file ), Files.getLastModifiedTime( file ).toMillis() )

    private fun isRuntimeFile( relative: String ): Boolean =
        relative.startsWith( "logs/" ) || relative.startsWith( "${EnvironmentRecorder.DIRECTORY}/" ) ||
            "/__pycache__/" in "/$relative" || relative.endsWith( ".pyc" )
}
