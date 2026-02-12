package carp.dsp.core.application.plan

import kotlinx.serialization.Serializable

@Serializable
sealed interface StepRunResult {
    val stepId: String
    val startedAtEpochMs: Long?
    val durationMs: Long?

    fun validate() {
        require(stepId.isNotBlank()) { "stepId must not be blank." }
        startedAtEpochMs?.let { require(it >= 0) { "startedAtEpochMs must be >= 0." } }
        durationMs?.let { require(it >= 0) { "durationMs must be >= 0." } }
    }
}
