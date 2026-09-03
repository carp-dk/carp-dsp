package carp.dsp.core.application.run

import java.io.File
import java.nio.file.Path

/**
 * Represents a workflow stored on disk: the directory used to resolve relative
 * paths and the name of the workflow file to read within that directory.
 *
 * The two properties are intentionally separate:
 *  - Resolution (for example locating a sibling `steps.lock`) needs the exact
 *    workflow file path.
 *  - Provisioning (for example resolving a step's declared file inputs) works
 *    against the containing directory.
 *
 * Keeping them distinct allows the same logic to handle an unpacked upload or
 * a VCS checkout by varying which directory is supplied while keeping the
 * workflow file name constant.
 */
data class WorkflowSource( val directory: Path, val fileName: String )
{
    /** The workflow file itself. */
    val file: File get() = directory.resolve( fileName ).toFile()

    companion object
    {
        /** The workflow at [path], resolved against its own directory. */
        fun of( path: Path ): WorkflowSource =
            path.toAbsolutePath().let { WorkflowSource( it.parent, it.fileName.toString() ) }

        /** The workflow at [path], resolved against its own directory. */
        fun of( path: String ): WorkflowSource = of( Path.of( path ) )
    }
}

/**
 * A workflow plus the files it ships with: scripts, data, anything its steps
 * reference by a relative path.
 *
 * This is what an upload is. A workflow on its own resolves `uses:` references
 * out of the step library, but a step that names `scripts/clean.py` needs that
 * file staged into the run, and only the package knows it exists.
 *
 * @property files Path relative to the execution root, to the file on disk.
 *   Commands run with the execution root as their working directory, so these
 *   are the paths the steps' own arguments use.
 */
data class WorkflowPackage(
    val source: WorkflowSource,
    val files: Map<String, Path> = emptyMap(),
)
{
    companion object
    {
        /**
         * Everything in [directory] except the workflow itself and its lock file.
         *
         * A package is a directory whose contents are all meant for the run, so
         * membership is by exclusion. Directories are walked; empty ones are
         * skipped, since there is nothing to stage.
         */
        fun of( directory: Path, workflowFileName: String ): WorkflowPackage
        {
            val root = directory.toAbsolutePath()
            val excluded = setOf( workflowFileName, "steps.lock" )

            val files = root.toFile().walkTopDown()
                .filter { it.isFile }
                .map { root.relativize( it.toPath() ).toString().replace( '\\', '/' ) to it.toPath() }
                .filterNot { ( relative, _ ) -> relative in excluded }
                .toMap()

            return WorkflowPackage( WorkflowSource( root, workflowFileName ), files )
        }
    }
}
