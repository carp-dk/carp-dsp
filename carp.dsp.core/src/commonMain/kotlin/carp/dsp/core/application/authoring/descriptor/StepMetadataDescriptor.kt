package carp.dsp.core.application.authoring.descriptor

import kotlinx.serialization.Serializable

/**
 * Descriptor for step-level metadata.
 *
 * Maps to [dk.cachet.carp.analytics.domain.workflow.StepMetadata] at import time.
 * The step's id lives on [StepDescriptor] itself, so this block is optional in YAML.
 *
 * @property name Human-readable step name. Defaults to the step id if absent.
 * @property description Optional longer-form description.
 * @property version Semantic version string for this step definition.
 * @property tags Free-form labels for search / filtering.
 */
@Serializable
data class StepMetadataDescriptor(
    val name: String? = null,
    val description: String? = null,
    val version: String = "1.0",
    val tags: List<String> = emptyList(),
)
