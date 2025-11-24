package carp.dsp.core.application.environment

import dk.cachet.carp.analytics.domain.environment.Environment
import kotlinx.serialization.Serializable

@Serializable
data class CondaEnvironment(
    override val name: String,
    override val dependencies: List<String> = listOf(),
    val pythonVersion: String = "3.11",
    val channels: List<String> = listOf("conda-forge")
) : Environment
