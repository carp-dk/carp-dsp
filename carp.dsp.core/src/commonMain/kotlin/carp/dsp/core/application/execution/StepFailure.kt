package carp.dsp.core.application.execution

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StepFailure(
    val kind: FailureKind,
    val message: String,
    val cause: String? = null,
    val info: FailureInfo? = null,
) {
    fun validate() {
        require(message.isNotBlank()) { "Failure message must not be blank." }
    }
}

@Serializable
enum class FailureKind {
    NON_ZERO_EXIT,
    TIMEOUT,
    SPAWN_FAILED,
    CONFIG_ERROR,
    INTERNAL_ERROR,
}

@Serializable
sealed interface FailureInfo

@Serializable
@SerialName("CommandInfo")
data class CommandInfo(
    val executable: String,
    val args: List<String> = emptyList(),
    val exitCode: Int? = null,
    val timedOut: Boolean = false,
    val stdout: String? = null,
    val stderr: String? = null,
) : FailureInfo {
    init {
        require(executable.isNotBlank()) { "executable must not be blank." }
        exitCode?.let { require(it >= 0) { "exitCode must be >= 0." } }
    }
}

@Serializable
@SerialName("InProcessInfo")
data class InProcessInfo(
    val operationId: String,
    val note: String? = null,
) : FailureInfo {
    init { require(operationId.isNotBlank()) { "operationId must not be blank." } }
}
