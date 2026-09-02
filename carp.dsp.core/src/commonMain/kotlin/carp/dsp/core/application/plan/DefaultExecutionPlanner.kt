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
 * 4. Validate protocol-bound inputs, when a protocol source is supplied
 * 5. Topologically sort steps
 * 6. Plan steps in sorted order using bindings resolution and compilation
 * 7. Construct final ExecutionPlan
 *
 * Validation is part of planning, not a separate pass: each stage accumulates
 * [PlanIssue]s into the resulting plan, and the executor refuses any plan where
 * [ExecutionPlan.isRunnable] is false. Protocol-bound inputs must therefore be
 * checked here - validating them outside the planner would leave the errors out
 * of `plan.issues` and reduce that guarantee to caller convention.
 *
 * Known trade-off: [protocolDataTypeProvider] is a constructor parameter because,
 * unlike the other collaborators, this check needs an external data source. A
 * second such check should not become a second parameter - at that point extract
 * a `PlanValidator` interface and take a list, so new validation kinds no longer
 * modify this class.
 *
 * @param protocolDataTypeProvider Optional source of study-protocol collected data
 *   types (e.g. [StudyProtocolSnapshotDataTypeProvider]). When present, every
 *   protocol-bound input is validated at plan time; when absent and the workflow
 *   has protocol-bound inputs, a `PROTOCOL_NOT_VALIDATED` warning is emitted.
 */
class DefaultExecutionPlanner(
    private val protocolDataTypeProvider: ProtocolDataTypeProvider? = null
) : ExecutionPlanner {

    private val logger = KotlinLogging.logger {}
    private val graphBuilder = DependencyGraphBuilder()
    private val sorter = DeterministicTopologicalSorter()
    private val bindingsResolver = BindingsResolver()
    private val stepCompiler = StepCompiler()
    private val envRefResolver = EnvironmentRefResolver()
    private val protocolValidator = ProtocolCouplingValidator()

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

        // Validate protocol-bound inputs against the study protocol(s) (F5)
        issues.addAll(protocolValidator.validate(steps, protocolDataTypeProvider))

        // Topological Sort
        val declarationOrder = steps.map { it.metadata.id }
        val order = sorter.sort(dag.adjacency, dag.indegree, declarationOrder)
        issues.addAll(order.issues)

        // Plan Steps (in sorted order)
        //
        // Indexed by id first: the sorted order gives ids, and looking each one up
        // with a scan over `steps` made planning quadratic in workflow size - the
        // only super-linear stage in a pipeline that is otherwise O(n + e). It is
        // invisible at the sizes measured, where a fixed cost dominates, which is
        // why it survived unnoticed.
        //
        // `putIfAbsent` rather than `associateBy`: duplicate step ids are a lint
        // error, but planning still runs on a workflow that has issues, and the
        // scan this replaces returned the *first* match. `associateBy` keeps the
        // last, which would quietly change which step gets planned in exactly the
        // case the author has already been told is broken.
        val stepsById = LinkedHashMap<UUID, Step>()
        steps.forEach { stepsById.putIfAbsent(it.metadata.id, it) }
        for ((executionIndex, stepId) in order.ordered.withIndex()) {
            val step = stepsById[stepId]
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

        logger.info { "Plan ready - ${plannedSteps.size} step(s), ${issues.size} issue(s)" }
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
