package carp.dsp.core.application.plan

import kotlinx.serialization.Serializable

/**
 * A planned, executable description of a workflow run.
 *
 * - Contains no runtime state and no environment instances.
 * - Produced by a planner; consumed by an engine.
 */
@Serializable
data class ExecutionPlan(
    val workflowId: String,
    val planId: String,
    val steps: List<PlannedStep>,
    // Planner issues. Errors make plan non-runnable.
    val issues: List<PlanIssue> = emptyList(),
    val requiredEnvironmentHandles: List<EnvironmentHandleRef> = emptyList(),
) {

    fun validate() {
        require(workflowId.isNotBlank()) { "workflowId must not be blank." }
        require(planId.isNotBlank()) { "planId must not be blank." }

        // ensuring stepIds are unique
        val ids = steps.map { it.stepId }
        require(ids.size == ids.distinct().size) {
            "steps contains duplicate stepId(s): ${ids.groupBy { it }.filterValues { it.size > 1 }.keys}"
        }

        // cross-object check: duplicates
        val handles = requiredEnvironmentHandles.map { it.handleId }
        require(handles.size == handles.distinct().size) {
            "requiredEnvironmentHandles contains duplicate handleId(s): ${
                handles.groupBy { it }.filterValues { it.size > 1 }.keys
            }"
        }
    }

    fun hasErrors(): Boolean = issues.any { it.severity == PlanIssueSeverity.ERROR }

    /**
     * Runnable means: planner produced no ERROR issues.
     * (Warnings/Info do not block.)
     */
    fun isRunnable(): Boolean = !hasErrors()
}

/**
 * Pure reference to an environment handle required for preflight.
 */
@Serializable
data class EnvironmentHandleRef(
    val handleId: String,
) {
    fun validate() {
        require(handleId.isNotBlank()) { "handleId must not be blank." }
    }
}
