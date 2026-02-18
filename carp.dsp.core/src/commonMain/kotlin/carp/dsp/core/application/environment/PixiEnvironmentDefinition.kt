package carp.dsp.core.application.environment

import dk.cachet.carp.analytics.domain.environment.EnvironmentDefinition
import dk.cachet.carp.common.application.UUID
import kotlinx.serialization.Serializable

@Serializable
data class PixiEnvironmentDefinition(
    override val id: UUID,
    override val name: String,
    override val dependencies: List<String> = listOf(),
    override val environmentVariables: Map<String, String> = emptyMap(),
    val pythonVersion: String = "3.11",
    val featureDeps: Map<String, List<String>> = emptyMap(),
    val channels: List<String> = listOf("conda-forge")
) : EnvironmentDefinition
