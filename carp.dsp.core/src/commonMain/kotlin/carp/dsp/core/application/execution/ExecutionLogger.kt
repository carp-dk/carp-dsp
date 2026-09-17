package carp.dsp.core.application.execution

import dk.cachet.carp.common.application.UUID

/**
 * Receives step lifecycle events during plan execution.
 * Implement this to observe when steps start, succeed, or fail.
 * Use [NoOpExecutionLogger] as the default — it is silent and has no overhead.
 */
interface ExecutionLogger {
    fun onStepStarted(runId: UUID, stepId: UUID, stepName: String)
    fun onStepCompleted(runId: UUID, stepId: UUID, stepName: String, durationMs: Long)
    fun onStepFailed(runId: UUID, stepId: UUID, stepName: String, reason: String)

    fun onEnvironmentSetupStarted(runId: UUID, environmentId: String, name: String) = Unit

    fun onEnvironmentReady(runId: UUID, outcome: EnvironmentOutcome) = Unit

    fun onEnvironmentFailed(runId: UUID, environmentId: String, name: String, reason: String) = Unit
}

/**
 * How a run got its environment.
 *
 * @property match `BUILT`, `EXACT` when a digest-identical environment already
 *   existed, or `SUPERSET` when a larger one was allowed to serve it.
 * @property extras what a `SUPERSET` environment carries that the workflow never
 *   asked for. Empty otherwise. Reported so the choice is auditable: a run that
 *   got packages it did not declare should say so.
 */
data class EnvironmentOutcome(
    val environmentId: String,
    val name: String,
    val durationMs: Long,
    val match: String,
    val extras: List<String> = emptyList(),
)

/**
 * No-op implementation — used as the default so callers that don't need
 * lifecycle events pay no overhead and require no configuration.
 */
object NoOpExecutionLogger : ExecutionLogger {
    override fun onStepStarted(runId: UUID, stepId: UUID, stepName: String) = Unit
    override fun onStepCompleted(runId: UUID, stepId: UUID, stepName: String, durationMs: Long) = Unit
    override fun onStepFailed(runId: UUID, stepId: UUID, stepName: String, reason: String) = Unit
}
