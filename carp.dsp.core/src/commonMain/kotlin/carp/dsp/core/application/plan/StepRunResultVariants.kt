package carp.dsp.core.application.plan

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("Succeeded")
data class StepSucceeded(
    override val stepId: String,
    override val startedAtEpochMs: Long? = null,
    override val durationMs: Long? = null,
    val detail: StepRunDetail? = null,
) : StepRunResult

@Serializable
@SerialName("Failed")
data class StepFailed(
    override val stepId: String,
    override val startedAtEpochMs: Long? = null,
    override val durationMs: Long? = null,
    val failure: StepFailure,
    val detail: StepRunDetail? = null,
) : StepRunResult {
    init { failure.validate() }
}

@Serializable
@SerialName("Skipped")
data class StepSkipped(
    override val stepId: String,
    override val startedAtEpochMs: Long? = null,
    override val durationMs: Long? = null,
    val reason: String,
) : StepRunResult {
    init { require(reason.isNotBlank()) { "reason must not be blank." } }
}
