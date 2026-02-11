package carp.dsp.core.application.plan

import kotlinx.serialization.Serializable

/**
 * A single executable step inside an [ExecutionPlan], with all runtime-relevant information resolved.
 */
@Serializable
data class PlannedStep(
    val stepId: String,
    val name: String,
    val process: ProcessRun,
    val bindings: ResolvedBindings,
    /**
     * TODO : This should match an EnvironmentHandle.key (or similar) in the plan.
     */
    val environmentKey: String? = null,
) {
    init {
        require(stepId.isNotBlank()) { "PlannedStep.stepId must not be blank." }
        require(name.isNotBlank()) { "PlannedStep.name must not be blank." }
        environmentKey?.let {
            require(
            it.isNotBlank()
        ) { "PlannedStep.environmentKey must not be blank when provided." }
        }
    }
}
