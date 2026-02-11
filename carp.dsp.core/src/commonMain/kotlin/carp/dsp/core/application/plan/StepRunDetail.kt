package carp.dsp.core.application.plan

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface StepRunDetail

@Serializable
@SerialName("CommandDetail")
data class CommandDetail(
    val executable: String,
    val args: List<String> = emptyList(),
    val exitCode: Int,
    val timedOut: Boolean = false,
    val stdout: String? = null,
    val stderr: String? = null,
) : StepRunDetail {
    init {
        require(executable.isNotBlank()) { "executable must not be blank." }
        require(exitCode >= 0) { "exitCode must be >= 0." }
    }
}

@Serializable
@SerialName("InProcessDetail")
data class InProcessDetail(
    val operationId: String,
    val note: String? = null,
) : StepRunDetail {
    init { require(operationId.isNotBlank()) { "operationId must not be blank." } }
}
