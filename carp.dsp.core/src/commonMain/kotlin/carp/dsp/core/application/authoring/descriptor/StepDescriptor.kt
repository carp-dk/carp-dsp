package carp.dsp.core.application.authoring.descriptor

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A workflow step descriptor interface.
 *
 * Maps to [dk.cachet.carp.analytics.domain.workflow.Step] at import time.
 *
 * @property id Optional UUID string identifying this step within the workflow.
 *   **Lenient read:** `null` is accepted; the importer generates a UUID.
 * @property metadata Optional step-level metadata. When absent the [id] is used as the step name.
 * @property dependsOn Ids of steps whose outputs must be produced before this step runs.
 * @property inputs Data-port descriptors for the step's input slots. On a
 *   referenced step these carry only the port id and `source` binding; the port
 *   descriptor is filled from the library step.
 */
@Serializable(with = StepDescriptor.Serializer::class)
sealed interface StepDescriptor
{
    val id: String?
    val metadata: StepMetadataDescriptor?
    val dependsOn: List<String>
    val inputs: List<DataPortDescriptor>

    /**
     * Content-based polymorphic serializer.
     *
     * A step with `uses:` decodes to [ReferencedStepDescriptor].
     * A step with `task:` to [DefinedStepDescriptor].
     * Having both or neither is an error.
     *
     * The union of both shapes exists only as [StepSurrogate], private to this
     * file, so callers keep the clean sealed types.
     */
    object Serializer : KSerializer<StepDescriptor>
    {
        override val descriptor: SerialDescriptor = StepSurrogate.serializer().descriptor

        override fun deserialize(decoder: Decoder): StepDescriptor =
            decoder.decodeSerializableValue(StepSurrogate.serializer()).toStep()

        override fun serialize(encoder: Encoder, value: StepDescriptor) =
            encoder.encodeSerializableValue(StepSurrogate.serializer(), StepSurrogate.from(value))
    }
}

/**
 * Self-contained step: its own task, environment and ports.
 *
 * @property environmentId Key in [WorkflowDescriptor.environments] for the environment this step runs in.
 * @property task The task this step runs.
 * @property outputs Data-port descriptors for the step's output slots.
 */
@Serializable
data class DefinedStepDescriptor(
    override val id: String? = null,
    override val metadata: StepMetadataDescriptor? = null,
    val environmentId: String,
    override val dependsOn: List<String> = emptyList(),
    val task: TaskDescriptor,
    override val inputs: List<DataPortDescriptor> = emptyList(),
    val outputs: List<DataPortDescriptor> = emptyList(),
) : StepDescriptor

/**
 * Reference to a library step; wiring (id, ordering, input sources) only, the rest resolves from the library.
 *
 * @property uses Library step reference, e.g. `"sensing.heartrate.clean"`.
 * @property version Optional explicit library-step version. When absent the
 *   reference resolves to the latest and the resolution is pinned in `steps.lock`.
 * @property args Optional argument overrides for the library step's task. Merged
 *   flag-by-flag over the library step's default args at resolution: a `--flag`
 *   present here overrides the library's value for that flag; flags only the
 *   library declares are kept; flags only the reference declares are appended.
 *   Lets one generic library step (e.g. a joiner) be configured per use.
 */
@Serializable
data class ReferencedStepDescriptor(
    override val id: String? = null,
    override val metadata: StepMetadataDescriptor? = null,
    val uses: String,
    val version: String? = null,
    override val dependsOn: List<String> = emptyList(),
    override val inputs: List<DataPortDescriptor> = emptyList(),
    val args: List<String>? = null,
) : StepDescriptor

/**
 * Flat form for a step; the only serialization shape with nullable fields.
 * [StepDescriptor.Serializer] maps it to and from the sealed types.
 */
@Serializable
private data class StepSurrogate(
    val id: String? = null,
    val uses: String? = null,
    val version: String? = null,
    val metadata: StepMetadataDescriptor? = null,
    val environmentId: String? = null,
    val dependsOn: List<String> = emptyList(),
    val task: TaskDescriptor? = null,
    val inputs: List<DataPortDescriptor> = emptyList(),
    val outputs: List<DataPortDescriptor> = emptyList(),
    val args: List<String>? = null,
)
{
    fun toStep(): StepDescriptor
    {
        val label = id ?: uses ?: "?"
        return when
        {
            uses != null && task != null ->
                throw SerializationException(
                    "Step '$label' has both 'uses' and 'task' - a step is one or the other."
                )
            uses != null ->
                ReferencedStepDescriptor(id, metadata, uses, version, dependsOn, inputs, args)
            task != null ->
                DefinedStepDescriptor(
                    id = id,
                    metadata = metadata,
                    environmentId = environmentId
                        ?: throw SerializationException("Step '$label' has a task but no environmentId."),
                    dependsOn = dependsOn,
                    task = task,
                    inputs = inputs,
                    outputs = outputs,
                )
            else ->
                throw SerializationException("Step '$label' has neither 'uses' nor 'task'.")
        }
    }

    companion object
    {
        fun from(step: StepDescriptor): StepSurrogate = when (step)
        {
            is DefinedStepDescriptor -> StepSurrogate(
                id = step.id,
                metadata = step.metadata,
                environmentId = step.environmentId,
                dependsOn = step.dependsOn,
                task = step.task,
                inputs = step.inputs,
                outputs = step.outputs,
            )
            is ReferencedStepDescriptor -> StepSurrogate(
                id = step.id,
                uses = step.uses,
                version = step.version,
                metadata = step.metadata,
                dependsOn = step.dependsOn,
                inputs = step.inputs,
                args = step.args,
            )
        }
    }
}
