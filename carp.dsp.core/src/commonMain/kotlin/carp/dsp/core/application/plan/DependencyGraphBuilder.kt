package carp.dsp.core.application.plan

import dk.cachet.carp.analytics.application.plan.PlanIssue
import dk.cachet.carp.analytics.application.plan.PlanIssueSeverity
import dk.cachet.carp.analytics.domain.workflow.Step
import dk.cachet.carp.common.application.UUID

/**
 * Builds a directed dependency graph from workflow steps based on cross-step data dependencies.
 *
 * A step A depends on step B if any of A's inputs come from B's outputs.
 * Uses `InputDataSpec.stepRef` to identify producer steps.
 *
 * **Collects structural validation issues** while building the graph.
 */
class DependencyGraphBuilder
{
    /**
     * Context for building the dependency graph.
     */
    private data class DependencyResolutionContext(
        val stepsById: Map<UUID, Step>,
        val stepsByName: Map<String, Step>,
        val stepsByDescriptorId: Map<String, Step>,
        val adjacency: MutableMap<UUID, MutableSet<UUID>>,
        val indegree: MutableMap<UUID, Int>,
        val issues: MutableList<PlanIssue>
    )
    /**
     * Builds adjacency list + indegree map and returns them together with collected PlanIssues.
     *
     * @param steps All workflow steps
     * @return DependencyGraphResult with adjacency, indegree, and validation issues
     */
    fun build( steps: List<Step> ): DependencyGraphResult
    {
        val issues = mutableListOf<PlanIssue>()
        val stepsById = steps.associateBy { it.metadata.id }
        val stepsByName = steps.associateBy { it.metadata.name }

        // Initialize adjacency list and indegree map
        val adjacency = mutableMapOf<UUID, MutableSet<UUID>>()
        val indegree = mutableMapOf<UUID, Int>()

        val stepsByDescriptorId = steps
            .mapNotNull { step -> step.metadata.descriptorId?.let { id -> id to step } }
            .toMap()

        // Initialize all steps with empty adjacency lists and zero indegree
        steps.forEach { step ->
            val stepId = step.metadata.id
            adjacency[stepId] = mutableSetOf()
            indegree[stepId] = 0
        }

        // Create context for dependency resolution
        val context = DependencyResolutionContext(
            stepsById = stepsById,
            stepsByName = stepsByName,
            stepsByDescriptorId = stepsByDescriptorId,
            adjacency = adjacency,
            indegree = indegree,
            issues = issues
        )

        // Build dependency relationships
        steps.forEach { step ->
            processDependenciesForStep(step, context)
        }

        // Convert to immutable collections
        val immutableAdjacency = adjacency.mapValues { it.value.toSet() }
        val immutableIndegree = indegree.toMap()

        return DependencyGraphResult(
            adjacency = immutableAdjacency,
            indegree = immutableIndegree,
            issues = issues.toList()
        )
    }

    /**
     * Processes all dependencies for a single step, validating them and building graph edges.
     */
    private fun processDependenciesForStep(
        step: Step,
        context: DependencyResolutionContext
    )
    {
        val stepId = step.metadata.id
        val dependencies = extractDependencies(step, context)
        val processedDependencies = mutableSetOf<UUID>()

        dependencies.forEach { dependency ->
            val isValidDependency = validateDependency(
                stepId,
                dependency.producerStepId,
                dependency.producerOutputId,
                context
            )

            if (
                isValidDependency &&
                dependency.producerStepId != null &&
                !processedDependencies.contains
                    (
                        dependency.producerStepId
                    )
                )
            {
                createDependencyEdge(dependency.producerStepId, stepId, context)
                processedDependencies.add(dependency.producerStepId)
            }
        }
    }

    /**
     * Validates a single dependency and returns true if it's valid.
     */
    private fun validateDependency(
        consumerStepId: UUID,
        producerId: UUID?,
        outputId: UUID?,
        context: DependencyResolutionContext
    ): Boolean
    {
        var isValid = true

        if ( producerId == null )
        {
            context.issues.add(
                PlanIssue(
                    severity = PlanIssueSeverity.ERROR,
                    code = "DEPENDENCY_PRODUCER_NOT_FOUND",
                    message = "Referenced producer step does not exist.",
                    stepId = consumerStepId
                )
            )
            isValid = false
        }

        if ( outputId == null && isValid )
        {
            context.issues.add(
                PlanIssue(
                    severity = PlanIssueSeverity.ERROR,
                    code = "DEPENDENCY_OUTPUT_NOT_FOUND",
                    message = "Producer step '${context.stepsById[producerId]?.metadata?.name ?: producerId}' " +
                            "does not have a matching output for this input.",
                    stepId = consumerStepId
                )
            )
            isValid = false
        }

        // Validate no self-dependency
        if ( consumerStepId == producerId )
        {
            validateNoSelfDependency( consumerStepId, producerId, context )
            isValid = false
        }

        return isValid
    }

    /**
     * Creates a dependency edge in the graph.
     *
     * Represents: producer → consumer (consumer depends on producer)
     */
    private fun createDependencyEdge(
        producerId: UUID,
        consumerStepId: UUID,
        context: DependencyResolutionContext
    )
    {
        // Add dependency edge: producer → consumer
        context.adjacency[producerId]?.add( consumerStepId )
        // Increment indegree for consumer
        context.indegree[consumerStepId] = context.indegree[consumerStepId]!! + 1
    }

    /**
     * Extracts all cross-step dependencies from a step's inputs.
     *
     * Returns a list of (producerId, outputId) pairs.
     */
    private fun extractDependencies( step: Step, context: DependencyResolutionContext ): List<CrossStepDependency>
    {
        return step.inputs.mapNotNull { input ->
            input.stepRef?.let { ref ->
                val producerStep = tryParseUuid( ref )?.let { context.stepsById[it] }
                    ?: context.stepsByName[ref]
                    ?: context.stepsByDescriptorId[ref]

                val producerOutput = producerStep?.outputs?.firstOrNull { it.name == input.name }

                CrossStepDependency(
                    producerStepRef = ref,
                    producerStepId = producerStep?.metadata?.id,
                    producerOutputId = producerOutput?.id,
                    outputName = input.name
                )
            }
        }
    }

    /**
     * Adds ERROR issue if step depends on itself.
     */
    private fun validateNoSelfDependency(
        stepId: UUID,
        producerId: UUID?,
        context: DependencyResolutionContext
    )
    {
        if ( stepId == producerId )
        {
            context.issues.add(
                PlanIssue(
                    severity = PlanIssueSeverity.ERROR,
                    code = "DEPENDENCY_SELF_REFERENCE",
                    message = "Step '$stepId' cannot depend on itself.",
                    stepId = stepId
                )
            )
        }
    }

    internal fun tryParseUuid( uuid: String ): UUID? =
        try { UUID.parse( uuid ) } catch ( _: Exception ) { null }
}

/**
 * Represents a cross-step dependency extracted from a step's input.
 */
data class CrossStepDependency(
    val producerStepRef: String, // original YAML ID — error messages
    val producerStepId: UUID?, // resolved producer step UUID
    val producerOutputId: UUID?, // resolved producer output UUID
    val outputName: String // output name — error messages
)

/**
 * Immutable result containing graph structure and collected validation issues.
 */
data class DependencyGraphResult(
    val adjacency: Map<UUID, Set<UUID>>,
    val indegree: Map<UUID, Int>,
    val issues: List<PlanIssue>
)
