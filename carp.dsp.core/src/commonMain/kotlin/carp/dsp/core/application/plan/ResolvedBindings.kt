package carp.dsp.core.application.plan

import kotlinx.serialization.Serializable

/**
 * Fully resolved bindings for a planned step.
 *
 * Executors consume this structure and must not depend on DataRegistry directly.
 */
@Serializable
data class ResolvedBindings(
    val inputs: Map<String, DataRef> = emptyMap(),
    val outputs: Map<String, DataSinkRef> = emptyMap(),
) {
    // Defensive copies to avoid shared mutable maps.
    val safeInputs: Map<String, DataRef> = inputs.toMap()
    val safeOutputs: Map<String, DataSinkRef> = outputs.toMap()

    init {
        // Lightweight validation: avoid blank keys (usually port names).
        require(inputs.keys.none { it.isBlank() }) { "ResolvedBindings.inputs contains a blank key (port name)." }
        require(outputs.keys.none { it.isBlank() }) { "ResolvedBindings.outputs contains a blank key (port name)." }
    }

    fun requireInput(name: String): DataRef =
        safeInputs[name] ?: throw IllegalArgumentException(
            "Missing required input binding '$name'. Available inputs: ${safeInputs.keys.sorted()}."
        )

    fun requireOutput(name: String): DataSinkRef =
        safeOutputs[name] ?: throw IllegalArgumentException(
            "Missing required output binding '$name'. Available outputs: ${safeOutputs.keys.sorted()}."
        )
}

/**
 * Concrete reference to readable data.
 * The [id] is typically a stable registry identifier; [type] is a semantic/format hint.
 */
@Serializable
data class DataRef(
    val id: String,
    val type: String,
) {
    init {
        require(id.isNotBlank()) { "DataRef.id must not be blank." }
        require(type.isNotBlank()) { "DataRef.type must not be blank." }
    }
}

/**
 * Concrete reference to a writable destination for data produced by a step.
 */
@Serializable
data class DataSinkRef(
    val id: String,
    val type: String,
) {
    init {
        require(id.isNotBlank()) { "DataSinkRef.id must not be blank." }
        require(type.isNotBlank()) { "DataSinkRef.type must not be blank." }
    }
}
