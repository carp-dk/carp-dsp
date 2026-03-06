package carp.dsp.core.application.authoring.descriptor

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Sealed interface for data output destinations at the descriptor level.
 *
 * These represent where data goes from a step's outputs.
 *
 * Maps to domain-level [dk.cachet.carp.analytics.domain.data.DataDestination] types during import.
 */
@Serializable
sealed interface OutputDestination

/**
 * File system destination for output data.
 *
 * @property path The local or network file system path where data should be written.
 *
 * Example YAML:
 * ```yaml
 * destination:
 *   type: "file"
 *   path: "/workspace/validated_eeg.edf"
 * ```
 */
@Serializable
@SerialName("file")
data class FileOutputDestination(val path: String) : OutputDestination

/**
 * Environment variable or registry key destination for output data.
 *
 * Stores output data in an in-memory registry under the given key,
 * typically for retrieval by downstream steps or external tools.
 *
 * @property variableName The name of the registry key or environment variable.
 *
 * Example YAML:
 * ```yaml
 * destination:
 *   type: "env-var"
 *   variableName: "CLEAN_EEG_PATH"
 * ```
 */
@Serializable
@SerialName("env-var")
data class EnvironmentVariableOutputDestination(val variableName: String) : OutputDestination
