package carp.dsp.core.infrastructure.execution

import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import dk.cachet.carp.common.application.UUID

/**
 * Determines the order in which steps of an [ExecutionPlan] are executed.
 *
 * Implementations are pure functions: given a plan they return a list of step IDs
 * in the desired execution sequence. No IO or side-effects are allowed here.
 */
fun interface StepOrderStrategy {
    /**
     * @param plan The plan whose steps need to be ordered.
     * @return Step IDs in the order they should be executed.
     */
    fun order(plan: ExecutionPlan): List<UUID>
}

/**
 * Default strategy: execute steps in the order the planner already placed them.
 *
 * The planner guarantees a valid topological order, so this is always safe
 * and requires no additional sorting work.
 */
object SequentialPlanOrder : StepOrderStrategy {
    override fun order(plan: ExecutionPlan): List<UUID> =
        plan.steps.map { it.stepId }
}

