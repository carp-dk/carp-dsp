package carp.dsp.core.application.plan

import dk.cachet.carp.analytics.application.plan.PlanIssue
import dk.cachet.carp.analytics.application.plan.PlanIssueSeverity
import dk.cachet.carp.common.application.UUID

/**
 * Transforms a validated dependency graph (DAG) into a deterministic execution order.
 *
 * Guarantees:
 * - All dependencies are managed
 * - The result is identical across runs
 * - Cycles are detected and raised as issues
 *
 * Uses topological sorting with deterministic tie-breaking based on workflow step declaration order.
 */
class DeterministicTopologicalSorter {

    /**
     * Sorts the dependency graph into a deterministic linear execution order.
     *
     * @param adjacency Map from step ID to set of dependent step IDs (producer -> consumers)
     * @param indegree Map from step ID to number of incoming dependencies
     * @param declarationOrder List of step IDs in workflow declaration order (for tie-breaking)
     * @return TopologicalSortResult containing the ordered list and any cycle detection issues
     */
    fun sort(
        adjacency: Map<UUID, Set<UUID>>,
        indegree: Map<UUID, Int>,
        declarationOrder: List<UUID>
    ): TopologicalSortResult {
        val issues = mutableListOf<PlanIssue>()
        val ordered = mutableListOf<UUID>()

        // Create a working copy to avoid mutating the original indegree map
        val workingIndegree = indegree.toMutableMap()
        val declarationRank = declarationOrder.mapIndexed { index, stepId -> stepId to index }.toMap()

        // Track total nodes for cycle detection
        val totalNodeCount = indegree.size

        // Initialize ready queue with all nodes that have indegree 0, sorted deterministically
        val readyQueue = initializeReadyQueue(workingIndegree, declarationRank)

        // Process nodes until ready queue is empty
        processAllReadyNodes(readyQueue, workingIndegree, adjacency, declarationRank, ordered)

        // Check for cycles
        checkForCycles(ordered.size, totalNodeCount, workingIndegree, issues)

        return TopologicalSortResult(
            ordered = ordered,
            issues = issues
        )
    }

    /**
     * Initializes the ready queue with nodes that have indegree 0.
     */
    private fun initializeReadyQueue(
        workingIndegree: Map<UUID, Int>,
        declarationRank: Map<UUID, Int>
    ): MutableList<UUID> {
        val readyQueue = mutableListOf<UUID>()
        workingIndegree.filter { it.value == 0 }.keys.forEach { nodeId ->
            readyQueue.add(nodeId)
        }
        sortReadyQueue(readyQueue, declarationRank)
        return readyQueue
    }

    /**
     * Processes all ready nodes until the queue is empty.
     */
    private fun processAllReadyNodes(
        readyQueue: MutableList<UUID>,
        workingIndegree: MutableMap<UUID, Int>,
        adjacency: Map<UUID, Set<UUID>>,
        declarationRank: Map<UUID, Int>,
        ordered: MutableList<UUID>
    ) {
        while (readyQueue.isNotEmpty()) {
            val nextNode = readyQueue.removeFirst()
            ordered.add(nextNode)
            workingIndegree.remove(nextNode)

            processDependentNodes(nextNode, adjacency, workingIndegree, readyQueue, declarationRank)
        }
    }

    /**
     * Processes the dependent nodes of a recently completed node.
     */
    private fun processDependentNodes(
        completedNode: UUID,
        adjacency: Map<UUID, Set<UUID>>,
        workingIndegree: MutableMap<UUID, Int>,
        readyQueue: MutableList<UUID>,
        declarationRank: Map<UUID, Int>
    ) {
        adjacency[completedNode]?.forEach { dependentNode ->
            updateDependentNode(dependentNode, workingIndegree, readyQueue, declarationRank)
        }
    }

    /**
     * Updates a dependent node's indegree and adds it to ready queue if it becomes ready.
     */
    private fun updateDependentNode(
        dependentNode: UUID,
        workingIndegree: MutableMap<UUID, Int>,
        readyQueue: MutableList<UUID>,
        declarationRank: Map<UUID, Int>
    ) {
        val currentIndegree = workingIndegree[dependentNode]
        if (currentIndegree == null || currentIndegree <= 0) return

        val newIndegree = currentIndegree - 1
        workingIndegree[dependentNode] = newIndegree

        if (newIndegree == 0) {
            readyQueue.add(dependentNode)
            sortReadyQueue(readyQueue, declarationRank)
        }
    }

    /**
     * Checks for cycles by comparing processed nodes vs total nodes.
     */
    private fun checkForCycles(
        processedCount: Int,
        totalNodeCount: Int,
        workingIndegree: Map<UUID, Int>,
        issues: MutableList<PlanIssue>
    ) {
        if (processedCount < totalNodeCount) {
            val remainingNodes = workingIndegree.keys.toSet()
            issues.add(createCycleDetectionIssue(remainingNodes, processedCount, totalNodeCount))
        }
    }

    /**
     * Sorts the ready queue deterministically based on declaration order.
     */
    private fun sortReadyQueue(
        readyQueue: MutableList<UUID>,
        declarationRank: Map<UUID, Int>
    ) {
        readyQueue.sortBy { stepId ->
            // Use declaration order rank, defaulting to Int.MAX_VALUE for nodes not in declaration order
            declarationRank[stepId] ?: Int.MAX_VALUE
        }
    }

    /**
     * Creates a PlanIssue for cycle detection with detailed information.
     */
    private fun createCycleDetectionIssue(
        remainingNodes: Set<UUID>,
        emittedCount: Int,
        totalCount: Int
    ): PlanIssue {
        return PlanIssue(
            severity = PlanIssueSeverity.ERROR,
            code = "WORKFLOW_CYCLE_DETECTED",
            message = "Circular dependency detected in workflow. Processed $emittedCount of $totalCount nodes. " +
                    "Steps involved in cycle: ${remainingNodes.map { it.toString() }.sorted().joinToString(", ")}",
            stepId = null
        )
    }
}

/**
 * Result of topological sorting operation.
 *
 * @param ordered List of step IDs in deterministic execution order
 * @param issues List of validation issues (e.g., cycle detection)
 */
data class TopologicalSortResult(
    val ordered: List<UUID>,
    val issues: List<PlanIssue>
) {
    /**
     * Returns true if the sorting was successful (no cycles detected).
     */
    val isValid: Boolean get() = issues.isEmpty()
}
