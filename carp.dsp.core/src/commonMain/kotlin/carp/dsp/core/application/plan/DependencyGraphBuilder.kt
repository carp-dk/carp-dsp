package carp.dsp.core.application.plan

import dk.cachet.carp.analytics.application.plan.PlanIssue
import dk.cachet.carp.analytics.application.plan.PlanIssueSeverity
import dk.cachet.carp.analytics.domain.data.StepOutputSource
import dk.cachet.carp.analytics.domain.workflow.Step
import dk.cachet.carp.common.application.UUID

/**
 * Builds a directed dependency graph from workflow steps based exclusively on StepOutputSource references
 * in step inputs, while collecting structural validation issues.
 */
class DependencyGraphBuilder {

    /**
     * Builds adjacency list + indegree map and returns them together with collected PlanIssues.
     */
    fun build(steps: List<Step>): DependencyGraphResult {
        val issues = mutableListOf<PlanIssue>()
        val stepsById = steps.associateBy { it.metadata.id }

        // Initialize adjacency list and indegree map
        val adjacency = mutableMapOf<UUID, MutableSet<UUID>>()
        val indegree = mutableMapOf<UUID, Int>()

        // Initialize all steps with empty adjacency lists and zero indegree
        steps.forEach { step ->
            val stepId = step.metadata.id
            adjacency[stepId] = mutableSetOf()
            indegree[stepId] = 0
        }

        // Build dependency relationships
        steps.forEach { step ->
            processDependenciesForStep(step, stepsById, adjacency, indegree, issues)
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
        stepsById: Map<UUID, Step>,
        adjacency: MutableMap<UUID, MutableSet<UUID>>,
        indegree: MutableMap<UUID, Int>,
        issues: MutableList<PlanIssue>
    ) {
        val stepId = step.metadata.id
        val dependencies = extractDependencies(step)

        // Track processed producer-consumer pairs to avoid duplicate indegree increments
        val processedDependencies = mutableSetOf<UUID>()

        dependencies.forEach { dependency ->
            val producerId = dependency.stepId
            val outputId = dependency.outputId

            val isValidDependency = validateDependency(stepId, producerId, outputId, stepsById, issues)

            // Only create edges for valid dependencies and avoid duplicates
            if (isValidDependency && !processedDependencies.contains(producerId)) {
                createDependencyEdge(producerId, stepId, adjacency, indegree)
                processedDependencies.add(producerId)
            }
        }
    }

    /**
     * Validates a single dependency and returns true if it's valid.
     */
    private fun validateDependency(
        consumerStepId: UUID,
        producerId: UUID,
        outputId: UUID,
        stepsById: Map<UUID, Step>,
        issues: MutableList<PlanIssue>
    ): Boolean {
        var isValid = true

        // Validate producer exists
        if (!stepsById.containsKey(producerId)) {
            validateProducerExists(producerId, stepsById, issues)
            isValid = false
        }

        // Validate no self-dependency
        if (consumerStepId == producerId) {
            validateNoSelfDependency(consumerStepId, producerId, issues)
            isValid = false
        }

        // If producer exists, validate the output exists
        stepsById[producerId]?.let { producer ->
            if (!producer.outputs.any { it.id == outputId }) {
                validateProducerOutputExists(producer, outputId, consumerStepId, issues)
                isValid = false
            }
        }

        return isValid
    }

    /**
     * Creates a dependency edge in the graph.
     */
    private fun createDependencyEdge(
        producerId: UUID,
        consumerStepId: UUID,
        adjacency: MutableMap<UUID, MutableSet<UUID>>,
        indegree: MutableMap<UUID, Int>
    ) {
        // Add dependency edge: producer -> consumer
        adjacency[producerId]?.add(consumerStepId)
        // Increment indegree for consumer
        indegree[consumerStepId] = indegree[consumerStepId]!! + 1
    }

    /**
     * Extracts all StepOutputSource references from a step's inputs.
     */
    private fun extractDependencies(step: Step): List<StepOutputSource> {
        val dependencies = mutableListOf<StepOutputSource>()

        step.inputs.forEach { input ->
            val source = input.source
            if (source is StepOutputSource) {
                dependencies.add(source)
            }
        }

        return dependencies
    }

    /**
     * Adds ERROR issue if referenced producer step does not exist.
     */
    private fun validateProducerExists(
        producerId: UUID,
        stepsById: Map<UUID, Step>,
        issues: MutableList<PlanIssue>
    ) {
        if (!stepsById.containsKey(producerId)) {
            issues.add(
                PlanIssue(
                    severity = PlanIssueSeverity.ERROR,
                    code = "DEPENDENCY_PRODUCER_NOT_FOUND",
                    message = "Referenced producer step '$producerId' does not exist in the workflow.",
                    stepId = null // This is a workflow-level issue
                )
            )
        }
    }

    /**
     * Adds ERROR issue if referenced producer output does not exist.
     */
    private fun validateProducerOutputExists(
        producer: Step,
        outputId: UUID,
        consumerStepId: UUID,
        issues: MutableList<PlanIssue>
    ) {
        val hasOutput = producer.outputs.any { it.id == outputId }
        if (!hasOutput) {
            issues.add(
                PlanIssue(
                    severity = PlanIssueSeverity.ERROR,
                    code = "DEPENDENCY_OUTPUT_NOT_FOUND",
                    message = "Step references non-existent output '$outputId' from producer step " +
                            "'${producer.metadata.name}'. Available outputs: " +
                            "${producer.outputs.map { it.id.toString() }.sorted()}",
                    stepId = consumerStepId // Attribute to the consumer step, not the producer
                )
            )
        }
    }

    /**
     * Adds ERROR issue if step depends on itself.
     */
    private fun validateNoSelfDependency(
        stepId: UUID,
        producerId: UUID,
        issues: MutableList<PlanIssue>
    ) {
        if (stepId == producerId) {
            issues.add(
                PlanIssue(
                    severity = PlanIssueSeverity.ERROR,
                    code = "DEPENDENCY_SELF_REFERENCE",
                    message = "Step '$stepId' cannot depend on itself.",
                    stepId = stepId
                )
            )
        }
    }
}

/**
 * Immutable result containing graph structure and collected validation issues.
 */
data class DependencyGraphResult(
    val adjacency: Map<UUID, Set<UUID>>,
    val indegree: Map<UUID, Int>,
    val issues: List<PlanIssue>
)
