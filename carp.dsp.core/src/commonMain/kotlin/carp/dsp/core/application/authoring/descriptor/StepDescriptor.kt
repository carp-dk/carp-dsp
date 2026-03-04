package carp.dsp.core.application.authoring.descriptor

import kotlinx.serialization.Serializable

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
