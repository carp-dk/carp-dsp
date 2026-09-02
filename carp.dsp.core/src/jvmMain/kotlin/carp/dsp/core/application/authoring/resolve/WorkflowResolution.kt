package carp.dsp.core.application.authoring.resolve

import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor
import java.io.File

/**
 * The resolve stage of the pipeline: turn a parsed workflow into one with every
 * `uses:` reference expanded, reading and writing the `steps.lock` beside it.
 *
 * Sits between parse and lint - callers pass the resolved descriptor on to the
 * linter, importer and planner, which then see only defined steps.
 */
object WorkflowResolution
{
    /**
     * Resolve [descriptor] against [library], pinning with (and updating) the
     * `steps.lock` beside [workflowFile].
     *
     * The lock is read to pin existing references and written back when it
     * changes - but only when the workflow actually has references, so an
     * all-inline workflow never grows a lock file.
     *
     * Returns the full [ResolutionResult] so callers can reach both the resolved
     * workflow and the impl files each library step brought in (for staging at
     * execution).
     */
    fun resolve(
        workflowFile: File,
        descriptor: WorkflowDescriptor,
        library: StepLibrary,
    ): ResolutionResult
    {
        val existing = StepsLockFile.read( workflowFile )
        val result = UsesResolver( library ).resolve( descriptor, existing )

        if ( result.lock.steps.isNotEmpty() && result.lock != existing )
        {
            StepsLockFile.write( workflowFile, result.lock )
        }
        return result
    }
}
