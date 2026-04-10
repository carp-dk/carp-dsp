package carp.dsp.core.application.authoring.mapper

import carp.dsp.core.application.authoring.descriptor.StepDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowMetadataDescriptor
import dk.cachet.carp.common.application.UUID

/**
 * Computes deterministic UUIDs for workflow components using UUID v5 hashing.
 * Ensures reproducibility: same input → same UUID every time.
 *
 * **Usage:**
 * ```kotlin
 * val resolver = DeterministicUUIDResolverImpl(workflowNamespace = UUID.randomUUID())
 * val stepId = resolver.resolveStepId(stepDescriptor)
 * val workflowName = resolver.resolveWorkflowId(metadataDescriptor)
 * ```
 */
interface DeterministicUUIDResolver
{
    /**
     * Resolve a step's UUID.
     *
     * If step descriptor has explicit ID: parses it as UUID.
     * Otherwise: generates deterministic UUID v5 based on step key.
     *
     * @param stepDescriptor The step to resolve
     * @return Deterministic UUID for the step
     */
    fun resolveStepId( stepDescriptor: StepDescriptor ): UUID

    /**
     * Resolve a workflow's UUID.
     *
     * If metadata has explicit ID: parses it as UUID.
     * Otherwise: generates deterministic UUID v5 based on workflow name.
     *
     * @param metadata The workflow metadata
     * @return Deterministic UUID for the workflow
     */
    fun resolveWorkflowId( metadata: WorkflowMetadataDescriptor ): UUID
}

/**
 * Default UUID resolver using deterministic UUID v5 hashing.
 *
 * @param namespace The namespace UUID for deterministic generation
 *   Typically derived from workflow ID for reproducibility
 */
class DeterministicUUIDResolverImpl(
    private val namespace: UUID
) : DeterministicUUIDResolver
{
    override fun resolveStepId( stepDescriptor: StepDescriptor ): UUID
    {
        // If explicit ID provided, use it
        stepDescriptor.id?.let { idString ->
            tryParseUuid( idString )?.let { return it }
        }

        // Otherwise: generate deterministic UUID v5
        val key = stepDescriptor.id ?: UNNAMED_STEP
        return DeterministicUUID.v5( namespace, "step:$key" )
    }

    override fun resolveWorkflowId( metadata: WorkflowMetadataDescriptor ): UUID
    {
        // If explicit ID provided, use it
        metadata.id?.let { idString ->
            tryParseUuid( idString )?.let { return it }
        }

        // Otherwise: generate deterministic UUID v5 from workflow name
        return DeterministicUUID.v5( namespace, "workflow:${metadata.name}" )
    }

    companion object
    {
        /**
         * Default key used when step has no ID.
         *
         * This constant is used in the UUID v5 computation, so changing it
         * will invalidate any previously generated UUIDs for unnamed steps.
         */
        private const val UNNAMED_STEP = "unnamed"

        /**
         * Attempt to parse a string as a UUID.
         *
         * Returns null if the string is not a valid UUID format.
         */
        private fun tryParseUuid( idString: String ): UUID?
        {
            return try
            {
                UUID.parse( idString )
            }
            catch ( _: IllegalArgumentException )
            {
                null
            }
        }
    }
}
