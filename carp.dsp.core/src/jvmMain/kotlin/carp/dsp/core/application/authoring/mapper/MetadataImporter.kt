package carp.dsp.core.application.authoring.mapper

import carp.dsp.core.application.authoring.descriptor.StepMetadataDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowMetadataDescriptor
import dk.cachet.carp.analytics.domain.workflow.StepMetadata
import dk.cachet.carp.analytics.domain.workflow.Version
import dk.cachet.carp.analytics.domain.workflow.WorkflowMetadata
import dk.cachet.carp.common.application.UUID

/**
 * Maps workflow and step-level metadata to domain metadata.
 *
 * workflow imports from the same YAML source.
 */
internal object MetadataImporter
{
    /**
     * Imports workflow metadata descriptor to domain model.
     *
     * @param workflowMetadataDescriptor The descriptor metadata
     * @param workflowNamespace The namespace UUID for deterministic ID generation
     * @return Domain WorkflowMetadata
     */
    fun importWorkflowMetadata(
        workflowMetadataDescriptor: WorkflowMetadataDescriptor,
        workflowNamespace: UUID
    ): WorkflowMetadata =
        WorkflowMetadata(
            id = workflowMetadataDescriptor.id?.let { tryParseUuid( it ) }
                ?: DeterministicUUID.v5(workflowNamespace, "metadata"),
            name = workflowMetadataDescriptor.name,
            description = workflowMetadataDescriptor.description,
            version = parseVersion( workflowMetadataDescriptor.version ),
        )

    /**
     * Imports step metadata descriptor to domain model.
     *
     * @param stepId The step UUID (already resolved)
     * @param stepMetadataDescriptor The descriptor metadata (can be null, defaults will be applied)
     * @return Domain StepMetadata
     */
    fun importStepMetadata(
        stepId: UUID,
        stepMetadataDescriptor: StepMetadataDescriptor?
    ): StepMetadata =
        StepMetadata(
            id = stepId,
            name = stepMetadataDescriptor?.name ?: stepId.toString(),
            description = stepMetadataDescriptor?.description,
            version = parseVersion( stepMetadataDescriptor?.version ?: "1.0" ),
        )

    /**
     * Parses a `"major.minor"` version string into a [Version].
     *
     * Accepts:
     * - `"1"` → `Version(1)`
     * - `"1.0"` → `Version(1, 0)`
     * - `"2.3"` → `Version(2, 3)`
     *
     * Falls back to `Version(1)` if the string cannot be parsed.
     */
    internal fun parseVersion(version: String ): Version
    {
        val parts = version.trim().split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return Version(1)
        val minor = parts.getOrNull(1)?.toIntOrNull()
        return Version(major, minor)
    }
}

/**
 * Attempts to parse a string as a UUID.
 *
 * @param uuid The string to parse
 * @return UUID if valid, null if parsing fails
 */
internal fun tryParseUuid(uuid: String ): UUID? =
    try { UUID.parse( uuid ) } catch (_: Exception ) { null }
