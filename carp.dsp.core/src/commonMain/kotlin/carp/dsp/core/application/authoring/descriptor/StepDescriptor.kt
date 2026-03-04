package carp.dsp.core.application.authoring.descriptor

import kotlinx.serialization.Serializable

// ── StepMetadataDescriptor ────────────────────────────────────────────────────

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

// ── DataPortDescriptor / DataDescriptor ───────────────────────────────────────

/**
 * Descriptor for a step's data input or output port.
 *
 * @property id Optional UUID string. Omit in authored YAML; the importer generates one.
 * @property descriptor Optional schema / format metadata for this port.
 */
@Serializable
data class DataPortDescriptor(
    val id: String? = null,
    val descriptor: DataDescriptor? = null,
)

/**
 * Lightweight descriptor for the type and format of data flowing through a port.
 *
 * @property type MIME type or domain type string (e.g. `"text/csv"`).
 * @property format Optional sub-format or dialect identifier.
 * @property schemaRef URI or short-name pointing to an external schema definition.
 * @property ontologyRef URI or CURIe pointing to an ontology concept.
 * @property notes Free-text documentation for this data port.
 */
@Serializable
data class DataDescriptor(
    val type: String? = null,
    val format: String? = null,
    val schemaRef: String? = null,
    val ontologyRef: String? = null,
    val notes: String? = null,
)

// ── StepDescriptor ────────────────────────────────────────────────────────────

/**
 * Descriptor for a workflow step.
 *
 * Maps to [dk.cachet.carp.analytics.domain.workflow.Step] at import time.
 *
 * @property id Optional UUID string identifying this step within the workflow.
 *   **Lenient read:** `null` is accepted; the importer generates a UUID.
 *   **Strict write:** exporters always emit a concrete, non-null value.
 * @property metadata Optional step-level metadata. When absent the [id] (or generated id) is used as the step name.
 * @property environmentId Key in [WorkflowDescriptor.environments] for the environment this step runs inside.
 * @property dependsOn List of step ids whose outputs must be produced before this step runs.
 * @property task Sealed task descriptor — one of [CommandTaskDescriptor], [PythonTaskDescriptor], or [InProcessTaskDescriptor].
 * @property inputs Data-port descriptors for the step's declared input slots.
 * @property outputs Data-port descriptors for the step's declared output slots.
 */
@Serializable
data class StepDescriptor(
    val id: String? = null,
    val metadata: StepMetadataDescriptor? = null,
    val environmentId: String,
    val dependsOn: List<String> = emptyList(),
    val task: TaskDescriptor,
    val inputs: List<DataPortDescriptor> = emptyList(),
    val outputs: List<DataPortDescriptor> = emptyList(),
)
