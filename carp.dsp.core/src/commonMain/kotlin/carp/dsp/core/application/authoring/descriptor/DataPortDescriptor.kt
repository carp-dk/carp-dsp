package carp.dsp.core.application.authoring.descriptor

import kotlinx.serialization.Serializable

/**
 * Descriptor for a step's data input or output port.
 *
 * Represents the contract for data flowing into or out of a step.
 * The presence of [source] vs [destination] depends on context (input vs output).
 *
 * @property id Optional UUID string. Omit in authored YAML; the importer generates one.
 * @property descriptor Optional schema / format metadata for this port.
 * @property source For **input** ports: optional specification of where data comes from.
 *   - If null: defaults to R1 placeholder ([dk.cachet.carp.analytics.domain.data.StepOutputSource] with zero UUID).
 *   - The importer uses best-effort to resolve; the linter validates references exist.
 * @property destination For **output** ports: optional specification of where data goes.
 *   - If null: defaults to R1 placeholder ([dk.cachet.carp.analytics.domain.data.FileDestination] with empty path).
 *   - Used to track output data lineage.
 */
@Serializable
data class DataPortDescriptor(
    val id: String? = null,
    val descriptor: DataDescriptor? = null,
    val source: InputSource? = null,
    val destination: OutputDestination? = null,
)
