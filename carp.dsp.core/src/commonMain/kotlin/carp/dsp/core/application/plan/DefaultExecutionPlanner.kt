package carp.dsp.core.application.plan

import carp.dsp.core.application.environment.EnvironmentRefResolver
import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import dk.cachet.carp.analytics.application.plan.ExecutionPlanner
import dk.cachet.carp.analytics.application.plan.PlanIssue
import dk.cachet.carp.analytics.application.plan.PlannedStep
import dk.cachet.carp.analytics.domain.workflow.Step
import dk.cachet.carp.analytics.domain.workflow.Workflow
import dk.cachet.carp.analytics.domain.workflow.WorkflowDefinition
import dk.cachet.carp.common.application.UUID
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * requiredEnvironmentRefs transforms a WorkflowDefinition (author-time model) into an ExecutionPlan (plan-time artefact).
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

    private val logger = KotlinLogging.logger {}
    private val graphBuilder = DependencyGraphBuilder()
    private val sorter = DeterministicTopologicalSorter()
    private val bindingsResolver = BindingsResolver()
    private val stepCompiler = StepCompiler()
    private val envRefResolver = EnvironmentRefResolver()

    /**
     * Transforms a WorkflowDefinition into an ExecutionPlan.
     *
     * @param definition The workflow definition to plan
     * @return ExecutionPlan containing planned steps and any planning issues
     */
    override fun plan( definition: WorkflowDefinition ): ExecutionPlan {
        logger.info { "Planning workflow '${definition.workflow.metadata.name}'" }
        // Initialize
        val issues = mutableListOf<PlanIssue>()
        val plannedSteps = mutableMapOf<UUID, PlannedStep>()

        // Flatten Steps - preserve declaration order
        val steps = collectSteps(definition.workflow)

        // Resolve Environment References (also validates and reports missing environments)
        val envRefs = envRefResolver.resolveEnvironments(
            steps,
            definition.environments,
            issues
        )


        // Build Dependency Graph
        val dag = graphBuilder.build(steps)
        issues.addAll(dag.issues)

        // Topological Sort
        val declarationOrder = steps.map { it.metadata.id }
        val order = sorter.sort(dag.adjacency, dag.indegree, declarationOrder)
        issues.addAll(order.issues)

        // Plan Steps (in sorted order)
        for ((executionIndex, stepId) in order.ordered.withIndex()) {
            val step = steps.find { it.metadata.id == stepId }
            if (step != null) {
                // Resolve bindings
                val bindings = bindingsResolver.resolve(
                    step,
                    plannedSteps,
                    issues,
                    executionIndex
                )
                // Compile step
                val compiled = stepCompiler.compile(step, bindings, issues)
                if (compiled != null) {
                    plannedSteps[stepId] = compiled
                }
            }
        }

        logger.info { "Plan ready — ${plannedSteps.size} step(s), ${issues.size} issue(s)" }
        // Construct ExecutionPlan
        return ExecutionPlan(
            workflowName = definition.workflow.metadata.name,
            planId = UUID.randomUUID().toString(),
            steps = plannedSteps.values.toList(),
            issues = issues.toList(),
            requiredEnvironmentRefs = envRefs
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
}
