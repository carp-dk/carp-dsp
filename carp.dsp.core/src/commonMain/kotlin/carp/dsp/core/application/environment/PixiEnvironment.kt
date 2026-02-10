package carp.dsp.core.application.environment

import dk.cachet.carp.analytics.domain.environment.Environment
import kotlinx.serialization.Serializable

@Serializable
data class PixiEnvironment(
    override val name: String,
    override val dependencies: List<String> = listOf(),
    val pythonVersion: String = "3.11",
    val featureDeps: Map<String, List<String>> = emptyMap(),
    val channels: List<String> = listOf("conda-forge")
) : Environment
