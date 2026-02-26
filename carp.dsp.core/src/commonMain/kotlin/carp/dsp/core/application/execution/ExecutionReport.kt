package carp.dsp.core.application.execution

import kotlinx.serialization.Serializable

@Serializable
data class ExecutionReport(
    val workflowId: String,
    val planId: String? = null,
    val startedAtEpochMs: Long? = null,
    val durationMs: Long? = null,
    val stepResults: List<StepRunResult> = emptyList(),
    val notes: List<String> = emptyList(),
) {

    fun validate() {
        require(workflowId.isNotBlank()) { "workflowId must not be blank." }
        planId?.let { require(it.isNotBlank()) { "planId must not be blank when provided." } }
        startedAtEpochMs?.let { require(it >= 0) { "startedAtEpochMs must be >= 0." } }
        durationMs?.let { require(it >= 0) { "durationMs must be >= 0." } }
        require(notes.none { it.isBlank() }) { "notes must not contain blank strings." }

        stepResults.forEach { it.validate() }
    }

    /**
     * Success for the overall run: no latest attempt of any step ended in failure.
     */
    fun isSuccessLatest(): Boolean =
        latestResultsByStepId().values.none { it is StepFailed }

    fun latestResultsByStepId(): Map<String, StepRunResult> =
        stepResults
            .groupBy { it.stepId }
            .mapValues { (_, results) -> results.last() }

    fun resultsFor(stepId: String): List<StepRunResult> =
        stepResults.filter { it.stepId == stepId }

    fun latestResultFor(stepId: String): StepRunResult? =
        stepResults.lastOrNull { it.stepId == stepId }

    fun failedResults(): List<StepFailed> = stepResults.filterIsInstance<StepFailed>()

    fun failedLatestResults(): List<StepFailed> =
        latestResultsByStepId().values.filterIsInstance<StepFailed>()

    fun succeededLatestResults(): List<StepSucceeded> =
        latestResultsByStepId().values.filterIsInstance<StepSucceeded>()

    fun skippedLatestResults(): List<StepSkipped> =
        latestResultsByStepId().values.filterIsInstance<StepSkipped>()
}
