package carp.dsp.core.application.execution

import dk.cachet.carp.common.application.UUID

/**
 * Receives step lifecycle events during plan execution.
 *
 * Implement this to observe when steps start, succeed, or fail.
 *
 * Use [NoOpExecutionLogger] as the default — it is silent and has no overhead.
 */
interface ExecutionLogger {
    fun onStepStarted(runId: UUID, stepId: UUID, stepName: String)
    fun onStepCompleted(runId: UUID, stepId: UUID, stepName: String, durationMs: Long)
    fun onStepFailed(runId: UUID, stepId: UUID, stepName: String, reason: String)
}

/**
 * No-op implementation — used as the default so callers that don't need
 * lifecycle events pay no overhead and require no configuration.
 */
object NoOpExecutionLogger : ExecutionLogger {
    override fun onStepStarted(runId: UUID, stepId: UUID, stepName: String) = Unit
    override fun onStepCompleted(runId: UUID, stepId: UUID, stepName: String, durationMs: Long) = Unit
    override fun onStepFailed(runId: UUID, stepId: UUID, stepName: String, reason: String) = Unit
}
