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
