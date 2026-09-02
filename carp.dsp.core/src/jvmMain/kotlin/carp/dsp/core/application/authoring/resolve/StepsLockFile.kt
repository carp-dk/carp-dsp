package carp.dsp.core.application.authoring.resolve

import com.charleskorn.kaml.Yaml
import java.io.File

/**
 * Reads and writes the `steps.lock` file that sits beside a workflow.
 *
 * The lock is committed alongside the workflow, so it uses the same YAML form as
 * the workflow itself.
 */
object StepsLockFile
{
    const val FILE_NAME = "steps.lock"

    private val yaml = Yaml.default

    /** The lock file that belongs beside [workflowFile]. */
    fun lockFileFor( workflowFile: File ): File = workflowFile.resolveSibling( FILE_NAME )

    /** The lock beside [workflowFile], or `null` when none has been written yet. */
    fun read( workflowFile: File ): StepsLock?
    {
        val file = lockFileFor( workflowFile )
        return if ( file.isFile ) yaml.decodeFromString( StepsLock.serializer(), file.readText() ) else null
    }

    /** Write [lock] beside [workflowFile]. */
    fun write( workflowFile: File, lock: StepsLock )
    {
        lockFileFor( workflowFile ).writeText( yaml.encodeToString( StepsLock.serializer(), lock ) )
    }
}
