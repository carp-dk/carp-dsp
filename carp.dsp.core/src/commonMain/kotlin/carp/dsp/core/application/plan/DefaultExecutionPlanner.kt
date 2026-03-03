package carp.dsp.core.application.plan

import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import dk.cachet.carp.analytics.application.plan.ExecutionPlanner
import dk.cachet.carp.analytics.application.plan.PlanIssue
import dk.cachet.carp.analytics.application.plan.PlanIssueSeverity
import dk.cachet.carp.analytics.application.plan.PlannedStep
import dk.cachet.carp.analytics.domain.workflow.Step
import dk.cachet.carp.analytics.domain.workflow.Workflow
import dk.cachet.carp.analytics.domain.workflow.WorkflowDefinition
import dk.cachet.carp.common.application.UUID

/**
 * DefaultExecutionPlanner transforms a WorkflowDefinition (author-time model) into an ExecutionPlan (plan-time artifact).
 *
 * The planner uses a multi-stage algorithm:
 * 1. Flatten workflow steps preserving declaration order
 * 2. Validate environment references
 * 3. Build dependency graph
 * 4. Topologically sort steps
 * 5. Plan steps in sorted order using bindings resolution and compilation
 * 6. Construct final ExecutionPlan
 */
class DefaultExecutionPlanner : ExecutionPlanner {

    private val graphBuilder = DependencyGraphBuilder()
    private val sorter = DeterministicTopologicalSorter()
    private val bindingsResolver = BindingsResolver()
    private val stepCompiler = StepCompiler()

    /**
     * Transforms a WorkflowDefinition into an ExecutionPlan.
     *
     * @param definition The workflow definition to plan
     * @return ExecutionPlan containing planned steps and any planning issues
     */
    override fun plan(definition: WorkflowDefinition): ExecutionPlan {
        // Initialize
        val issues = mutableListOf<PlanIssue>()
        val plannedSteps = mutableMapOf<UUID, PlannedStep>()
        val requiredEnvironments = mutableSetOf<UUID>()

        // Flatten Steps - preserve declaration order
        val steps = collectSteps(definition.workflow)

        // Validate Environment References
        validateEnvironmentReferences(definition, steps, issues)

        // Build Dependency Graph
        val dag = graphBuilder.build(steps)
        issues.addAll(dag.issues)

        // Topological Sort
        val declarationOrder = steps.map { it.metadata.id }
        val order = sorter.sort(dag.adjacency, dag.indegree, declarationOrder)
        issues.addAll(order.issues)

        // Plan Steps (in sorted order)
        for (stepId in order.ordered) {
            val step = steps.find { it.metadata.id == stepId }
            if (step != null) {
                // Resolve bindings
                val bindings = bindingsResolver.resolve(step, plannedSteps, issues)

                // Compile step
                val compiled = stepCompiler.compile(step, bindings, issues)
                if (compiled != null) {
                    plannedSteps[stepId] = compiled
                    requiredEnvironments.add(step.environmentId)
                }
            }
        }

        // Construct ExecutionPlan
        return ExecutionPlan(
            workflowId = definition.workflow.metadata.id.toString(),
            planId = UUID.randomUUID().toString(),
            steps = plannedSteps.values.toList(),
            issues = issues.toList(),
            requiredEnvironmentHandles = requiredEnvironments.toList().sortedBy { it.toString() }
        )
    }

    /**
     * Recursively flattens workflow components into a list of steps,
     * preserving declaration order for deterministic processing.
     */
    private fun collectSteps(workflow: Workflow): List<Step> {
        val steps = mutableListOf<Step>()

        for (component in workflow.getComponents()) {
            when (component) {
                is Step -> steps.add(component)
                is Workflow -> steps.addAll(collectSteps(component))
            }
        }

        return steps.toList()
    }

    /**
     * Validates that all step environment references exist in the definition.
     */
    private fun validateEnvironmentReferences(
        definition: WorkflowDefinition,
        steps: List<Step>,
        issues: MutableList<PlanIssue>
    ) {
        val availableEnvironments = definition.environments.keys

        for (step in steps) {
            if (step.environmentId !in availableEnvironments) {
                issues.add(
                    PlanIssue(
                        severity = PlanIssueSeverity.ERROR,
                        code = "MISSING_ENVIRONMENT",
                        message = "Step '${step.metadata.name}' references environment '${step.environmentId}' " +
                                "which is not defined in the workflow definition. " +
                                "Available environments: ${availableEnvironments.map { it.toString() }.sorted()}.",
                        stepId = step.metadata.id
                    )
                )
            }
        }
    }
}
