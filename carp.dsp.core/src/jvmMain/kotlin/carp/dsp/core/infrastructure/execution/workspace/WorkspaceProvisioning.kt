package carp.dsp.core.infrastructure.execution.workspace

import dk.cachet.carp.common.application.UUID
import java.nio.file.Path

/**
 * A single file to place into a step's working directory before it runs.
 *
 * Unifies the two things a resolved step needs staged: implementation code that
 * lives as content (from the library, not on disk) and input files that live as a
 * path to copy. Both are "put this at this relative path"; they differ only in
 * where the bytes come from.
 */
sealed interface FileSource
{
    /** Literal bytes to write - e.g. a library step's impl script, held in memory. */
    data class Content( val text: String ) : FileSource

    /** An existing file to copy in - e.g. a declared input authored beside the workflow. */
    data class Copy( val from: Path ) : FileSource
}

/**
 * The files a step contributes, keyed by their path relative to the execution root
 * - the working directory its command runs in. The key is the step so a staging
 * collision (two steps, same path, different bytes) can be attributed and refused.
 */
typealias StepFiles = Map<String, FileSource>

/**
 * Everything a run needs materialised on disk before it executes, keyed by domain
 * step id. Produced during resolution and authoring (impl code and declared
 * inputs), consumed by [WorkspaceProvisioner] when the workspace is created.
 *
 * This is the provisioning half of a runnable workflow - the physical materials,
 * kept separate from the logical [dk.cachet.carp.analytics.application.plan.ExecutionPlan]
 * so the plan stays a pure DAG and is not re-serialised to carry file bytes.
 */
data class WorkspaceProvisioning(
    val byStep: Map<UUID, StepFiles> = emptyMap(),
)
{
    val isEmpty: Boolean get() = byStep.isEmpty()

    companion object
    {
        val EMPTY = WorkspaceProvisioning()
    }
}
