package carp.dsp.core.application.authoring.descriptor

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Sealed interface for data input sources at the descriptor level.
 *
 * These represent where data comes from for a step's inputs.
 *
 * Maps to domain-level [dk.cachet.carp.analytics.domain.data.DataSourceType] types during import.
 */
@Serializable
sealed interface InputSource

/**
 * File system source for input data.
 *
 * @property path The local or network file system path to the data file.
 *
 * Example YAML:
 * ```yaml
 * source:
 *   type: "file"
 *   path: "./data/raw_eeg.edf"
 * ```
 */
@Serializable
@SerialName("file")
data class FileInputSource(val path: String) : InputSource

/**
 * Step output source for input data (upstream step).
 *
 * References another step's output port as the source of this input.
 * The importer uses best-effort to resolve the reference; the linter
 * validates that the referenced step and output actually exist.
 *
 * @property stepId The semantic (string) ID of the upstream step.
 * @property outputId The semantic (string) ID of the upstream step's output port.
 *
 * Example YAML:
 * ```yaml
 * source:
 *   type: "step-output"
 *   outputId: "validated-eeg"
 * ```
 */
@Serializable
@SerialName("step-output")
data class StepOutputInputSource(
    val stepId: String,
    val outputId: String
) : InputSource

/**
 * Environment variable or registry key source for input data.
 *
 * References a named value stored in environment variables or an in-memory registry.
 * Useful for passing configuration, paths, or external data into the workflow.
 *
 * @property variableName The name of the environment variable or registry key.
 *
 * Example YAML:
 * ```yaml
 * source:
 *   type: "env-var"
 *   variableName: "EEG_DATA_PATH"
 * ```
 */
@Serializable
@SerialName("env-var")
data class EnvironmentVariableInputSource(val variableName: String) : InputSource

/**
 * Reference to a study protocol, used by [ProtocolInputSource].
 *
 * A protocol is identified by its id ([dk.cachet.carp.protocols.domain.StudyProtocol] `id`);
 * the name is a human-readable label only, never a key (protocol names are unique
 * only per owner). Protocols are versioned; when [version] is omitted the latest
 * version is assumed.
 *
 * @property id UUID string of the study protocol.
 * @property version Optional protocol version; defaults to latest.
 * @property name Optional human-readable label, for documentation only.
 */
@Serializable
data class ProtocolRefDescriptor(
    val id: String,
    val version: Int? = null,
    val name: String? = null,
)

/**
 * Study-protocol source for a boundary input: data collected by a CARP study protocol.
 *
 * Declares that this input's data comes from a study protocol's data collection.
 * At plan time the planner verifies the [dataType] is collected by the referenced
 * protocol and rejects the workflow otherwise.
 * Because binding is per input, different inputs may reference different
 * protocols in the same workflow.
 *
 * @property protocol Reference to the study protocol collecting this data.
 * @property dataType Fully namespaced CARP data type expected from the protocol
 *   (e.g. `"dk.cachet.carp.heartrate"`). This is the domain data type, distinct
 *   from the port's `descriptor.fileFormat` (e.g. `"csv"`).
 *
 * Example YAML:
 * ```yaml
 * source:
 *   type: "protocol"
 *   protocol:
 *     id: "aabbccdd-0000-0000-0000-000000000000"
 *     version: 2
 *     name: "Gait study"
 *   dataType: "dk.cachet.carp.heartrate"
 * ```
 */
@Serializable
@SerialName("protocol")
data class ProtocolInputSource(
    val protocol: ProtocolRefDescriptor,
    val dataType: String,
) : InputSource

/**
 * External source for a boundary input: open data, an upload, or a prior export -
 * anything not collected by a study protocol.
 *
 * External data is a designed affordance, not a gap: it is never validated against
 * a protocol, and may be mixed freely with [ProtocolInputSource] inputs in one
 * workflow. Best practice is to attribute it via [uri] and [citation]; when both
 * are absent the linter emits a warning (unattributed external data), never an
 * error. A boundary input with no `source` at all is treated as an empty external.
 *
 * @property uri Optional public URL/URI of the dataset.
 * @property citation Optional citation for the dataset.
 *
 * Example YAML:
 * ```yaml
 * source:
 *   type: "external"
 *   uri: "https://zenodo.org/record/53894"
 *   citation: "Furberg et al. 2016"
 * ```
 */
@Serializable
@SerialName("external")
data class ExternalInputSource(
    val uri: String? = null,
    val citation: String? = null,
) : InputSource
