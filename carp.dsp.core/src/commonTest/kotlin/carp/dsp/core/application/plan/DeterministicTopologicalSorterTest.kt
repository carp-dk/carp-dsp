package carp.dsp.core.application.plan

import dk.cachet.carp.common.application.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Comprehensive test suite for DeterministicTopologicalSorter.
 *
 * Tests cover:
 * - Empty graphs
 * - Single nodes
 * - Linear chains
 * - Fan-out patterns
 * - Fan-in patterns
 * - Complex DAGs
 * - Deterministic tie-breaking
 * - Cycle detection
 */
class DeterministicTopologicalSorterTest {

    private val sorter = DeterministicTopologicalSorter()

    @Test
    fun `sort with empty graph returns empty result`() {
        val result = sorter.sort(
            adjacency = emptyMap(),
            indegree = emptyMap(),
            declarationOrder = emptyList()
        )

        assertTrue(result.ordered.isEmpty())
        assertTrue(result.issues.isEmpty())
        assertTrue(result.isValid)
    }

    @Test
    fun `sort with single node returns that node`() {
        val nodeId = UUID.randomUUID()

        val result = sorter.sort(
            adjacency = mapOf(nodeId to emptySet()),
            indegree = mapOf(nodeId to 0),
            declarationOrder = listOf(nodeId)
        )

        assertEquals(listOf(nodeId), result.ordered)
        assertTrue(result.issues.isEmpty())
        assertTrue(result.isValid)
    }

    @Test
    fun `sort with linear chain preserves dependency order`() {
        val node1 = UUID.randomUUID()
        val node2 = UUID.randomUUID()
        val node3 = UUID.randomUUID()

        // node1 -> node2 -> node3
        val adjacency = mapOf(
            node1 to setOf(node2),
            node2 to setOf(node3),
            node3 to emptySet()
        )
        val indegree = mapOf(
            node1 to 0,
            node2 to 1,
            node3 to 1
        )

        val result = sorter.sort(adjacency, indegree, listOf(node1, node2, node3))

        assertEquals(listOf(node1, node2, node3), result.ordered)
        assertTrue(result.isValid)
    }

    @Test
    fun `sort with fan-out pattern respects dependencies`() {
        val node1 = UUID.randomUUID()
        val node2 = UUID.randomUUID()
        val node3 = UUID.randomUUID()

        // node1 -> node2, node3
        val adjacency = mapOf(
            node1 to setOf(node2, node3),
            node2 to emptySet(),
            node3 to emptySet()
        )
        val indegree = mapOf(
            node1 to 0,
            node2 to 1,
            node3 to 1
        )

        val result = sorter.sort(adjacency, indegree, listOf(node1, node2, node3))

        // node1 must be first, then node2 and node3 in declaration order
        assertEquals(listOf(node1, node2, node3), result.ordered)
        assertTrue(result.isValid)
    }

    @Test
    fun `sort with fan-in pattern respects dependencies`() {
        val node1 = UUID.randomUUID()
        val node2 = UUID.randomUUID()
        val node3 = UUID.randomUUID()

        // node1, node2 -> node3
        val adjacency = mapOf(
            node1 to setOf(node3),
            node2 to setOf(node3),
            node3 to emptySet()
        )
        val indegree = mapOf(
            node1 to 0,
            node2 to 0,
            node3 to 2
        )

        val result = sorter.sort(adjacency, indegree, listOf(node1, node2, node3))

        // node1 and node2 can be in any order (but deterministic), then node3
        assertTrue(result.ordered.indexOf(node1) < result.ordered.indexOf(node3))
        assertTrue(result.ordered.indexOf(node2) < result.ordered.indexOf(node3))
        assertEquals(3, result.ordered.size)
        assertTrue(result.isValid)
    }

    @Test
    fun `sort applies deterministic tie-breaking based on declaration order`() {
        val node1 = UUID.randomUUID()
        val node2 = UUID.randomUUID()
        val node3 = UUID.randomUUID()

        // All nodes have indegree 0 - tie-breaking should use declaration order
        val adjacency = mapOf<UUID, Set<UUID>>(
            node1 to emptySet(),
            node2 to emptySet(),
            node3 to emptySet()
        )
        val indegree = mapOf(
            node1 to 0,
            node2 to 0,
            node3 to 0
        )

        // Test different declaration orders
        val result1 = sorter.sort(adjacency, indegree, listOf(node1, node2, node3))
        assertEquals(listOf(node1, node2, node3), result1.ordered)

        val result2 = sorter.sort(adjacency, indegree, listOf(node3, node1, node2))
        assertEquals(listOf(node3, node1, node2), result2.ordered)

        val result3 = sorter.sort(adjacency, indegree, listOf(node2, node3, node1))
        assertEquals(listOf(node2, node3, node1), result3.ordered)

        // All results should be valid
        assertTrue(result1.isValid)
        assertTrue(result2.isValid)
        assertTrue(result3.isValid)
    }

    @Test
    fun `sort with complex DAG produces valid ordering`() {
        // Complex dependency pattern: A -> C, B -> C, C -> D, C -> E, D -> F, E -> F
        val nodeA = UUID.randomUUID()
        val nodeB = UUID.randomUUID()
        val nodeC = UUID.randomUUID()
        val nodeD = UUID.randomUUID()
        val nodeE = UUID.randomUUID()
        val nodeF = UUID.randomUUID()

        val adjacency = mapOf(
            nodeA to setOf(nodeC),
            nodeB to setOf(nodeC),
            nodeC to setOf(nodeD, nodeE),
            nodeD to setOf(nodeF),
            nodeE to setOf(nodeF),
            nodeF to emptySet()
        )
        val indegree = mapOf(
            nodeA to 0,
            nodeB to 0,
            nodeC to 2,
            nodeD to 1,
            nodeE to 1,
            nodeF to 2
        )

        val result = sorter.sort(adjacency, indegree, listOf(nodeA, nodeB, nodeC, nodeD, nodeE, nodeF))

        // Verify all dependencies are respected
        assertTrue(result.ordered.indexOf(nodeA) < result.ordered.indexOf(nodeC))
        assertTrue(result.ordered.indexOf(nodeB) < result.ordered.indexOf(nodeC))
        assertTrue(result.ordered.indexOf(nodeC) < result.ordered.indexOf(nodeD))
        assertTrue(result.ordered.indexOf(nodeC) < result.ordered.indexOf(nodeE))
        assertTrue(result.ordered.indexOf(nodeD) < result.ordered.indexOf(nodeF))
        assertTrue(result.ordered.indexOf(nodeE) < result.ordered.indexOf(nodeF))

        assertEquals(6, result.ordered.size)
        assertTrue(result.isValid)
    }

    @Test
    fun `sort detects simple cycle`() {
        val node1 = UUID.randomUUID()
        val node2 = UUID.randomUUID()

        // node1 -> node2 -> node1 (cycle)
        val adjacency = mapOf(
            node1 to setOf(node2),
            node2 to setOf(node1)
        )
        val indegree = mapOf(
            node1 to 1,
            node2 to 1
        )

        val result = sorter.sort(adjacency, indegree, listOf(node1, node2))

        assertTrue(result.ordered.isEmpty()) // No nodes can be processed
        assertEquals(1, result.issues.size)
        assertEquals("WORKFLOW_CYCLE_DETECTED", result.issues[0].code)
        assertFalse(result.isValid)

        // Verify cycle detection message includes both nodes and count information
        val message = result.issues[0].message
        assertTrue(message.contains("Processed 0 of 2 nodes"))
        assertTrue(message.contains(node1.toString()))
        assertTrue(message.contains(node2.toString()))
    }

    @Test
    fun `sort detects complex cycle`() {
        val node1 = UUID.randomUUID()
        val node2 = UUID.randomUUID()
        val node3 = UUID.randomUUID()
        val node4 = UUID.randomUUID()

        // node1 -> node2 -> node3 -> node1 (cycle), node4 independent
        val adjacency = mapOf(
            node1 to setOf(node2),
            node2 to setOf(node3),
            node3 to setOf(node1),
            node4 to emptySet()
        )
        val indegree = mapOf(
            node1 to 1,
            node2 to 1,
            node3 to 1,
            node4 to 0
        )

        val result = sorter.sort(adjacency, indegree, listOf(node1, node2, node3, node4))

        // node4 should be processed (not in cycle)
        assertEquals(listOf(node4), result.ordered)
        assertEquals(1, result.issues.size)
        assertEquals("WORKFLOW_CYCLE_DETECTED", result.issues[0].code)
        assertFalse(result.isValid)

        // Verify cycle detection includes count information
        val message = result.issues[0].message
        assertTrue(message.contains("Processed 1 of 4 nodes"))
    }

    @Test
    fun `sort handles nodes not in declaration order`() {
        val node1 = UUID.randomUUID()
        val node2 = UUID.randomUUID()
        val node3 = UUID.randomUUID()

        val adjacency = mapOf<UUID, Set<UUID>>(
            node1 to emptySet(),
            node2 to emptySet(),
            node3 to emptySet()
        )
        val indegree = mapOf(
            node1 to 0,
            node2 to 0,
            node3 to 0
        )

        // Declaration order only includes node1 and node3
        val result = sorter.sort(adjacency, indegree, listOf(node1, node3))

        // Should still process all nodes, with missing node last
        assertEquals(3, result.ordered.size)
        assertTrue(result.ordered.indexOf(node1) < result.ordered.indexOf(node3))
        // node2 should be last (Int.MAX_VALUE rank)
        assertEquals(node2, result.ordered.last())
        assertTrue(result.isValid)
    }

    @Test
    fun `sort result isValid reflects presence of issues`() {
        val validResult = TopologicalSortResult(
            ordered = listOf(UUID.randomUUID()),
            issues = emptyList()
        )
        assertTrue(validResult.isValid)

        val invalidResult = TopologicalSortResult(
            ordered = emptyList(),
            issues = listOf(
                dk.cachet.carp.analytics.application.plan.PlanIssue(
                    severity = dk.cachet.carp.analytics.application.plan.PlanIssueSeverity.ERROR,
                    code = "TEST_ERROR",
                    message = "Test error",
                    stepId = null
                )
            )
        )
        assertFalse(invalidResult.isValid)
    }

    @Test
    fun `sort handles successful completion without any remaining nodes`() {
        val node1 = UUID.randomUUID()
        val node2 = UUID.randomUUID()

        // Simple chain to test the successful break path (line 48)
        val adjacency = mapOf(
            node1 to setOf(node2),
            node2 to emptySet()
        )
        val indegree = mapOf(
            node1 to 0,
            node2 to 1
        )

        val result = sorter.sort(adjacency, indegree, listOf(node1, node2))

        // Should complete successfully with no issues (line 69 with empty issues)
        assertEquals(listOf(node1, node2), result.ordered)
        assertTrue(result.issues.isEmpty()) // Tests line 69 with empty issues list
        assertTrue(result.isValid)
    }

    @Test
    fun `sort handles adjacency with null values gracefully`() {
        val node1 = UUID.randomUUID()
        val node2 = UUID.randomUUID()
        val node3 = UUID.randomUUID()

        // Create adjacency where node2 has no entry (tests null handling in line 59)
        val adjacency = mapOf(
            node1 to setOf(node2),
            // node2 intentionally missing to test null adjacency handling
            node3 to emptySet()
        )
        val indegree = mapOf(
            node1 to 0,
            node2 to 1,
            node3 to 0
        )

        val result = sorter.sort(adjacency, indegree, listOf(node1, node2, node3))

        // Should handle missing adjacency entries gracefully
        assertTrue(result.isValid)
        assertEquals(3, result.ordered.size)
        assertTrue(result.ordered.contains(node1))
        assertTrue(result.ordered.contains(node2))
        assertTrue(result.ordered.contains(node3))
    }

    @Test
    fun `sort handles dependent node with zero indegree correctly`() {
        val node1 = UUID.randomUUID()
        val node2 = UUID.randomUUID()
        val node3 = UUID.randomUUID()

        // Test scenario where dependent node already has indegree 0 (line 61 condition)
        val adjacency = mapOf(
            node1 to setOf(node2, node3),
            node2 to emptySet(),
            node3 to emptySet()
        )
        val indegree = mapOf(
            node1 to 0,
            node2 to 0, // Already 0, so line 61 condition (currentIndegree > 0) will be false
            node3 to 1
        )

        val result = sorter.sort(adjacency, indegree, listOf(node1, node2, node3))

        assertTrue(result.isValid)
        assertEquals(3, result.ordered.size)
    }


    @Test
    fun `sort creates detailed cycle detection message`() {
        val node1 = UUID.randomUUID()
        val node2 = UUID.randomUUID()
        val node3 = UUID.randomUUID()

        // Create a 3-node cycle to test the cycle detection message formatting (lines 88, 91)
        val adjacency = mapOf(
            node1 to setOf(node2),
            node2 to setOf(node3),
            node3 to setOf(node1)
        )
        val indegree = mapOf(
            node1 to 1,
            node2 to 1,
            node3 to 1
        )

        val result = sorter.sort(adjacency, indegree, listOf(node1, node2, node3))

        // Test detailed cycle detection (line 91 - PlanIssue construction)
        assertFalse(result.isValid)
        assertEquals(1, result.issues.size)

        val issue = result.issues[0]
        assertEquals("WORKFLOW_CYCLE_DETECTED", issue.code)
        assertEquals(dk.cachet.carp.analytics.application.plan.PlanIssueSeverity.ERROR, issue.severity)
        assertNull(issue.stepId) // Should be null for workflow-level issues

        // Verify message contains all cycle nodes in sorted order (line 95)
        val message = issue.message
        assertTrue(message.contains("Circular dependency detected"))
        assertTrue(message.contains(node1.toString()))
        assertTrue(message.contains(node2.toString()))
        assertTrue(message.contains(node3.toString()))
        assertTrue(message.contains(", ")) // Tests the joinToString formatting
    }

    @Test
    fun `sort handles mixed successful and failed completion paths`() {
        val readyNode = UUID.randomUUID()
        val cyclicNode1 = UUID.randomUUID()
        val cyclicNode2 = UUID.randomUUID()

        // Mix of ready nodes and cyclic nodes
        val adjacency = mapOf(
            readyNode to emptySet(),
            cyclicNode1 to setOf(cyclicNode2),
            cyclicNode2 to setOf(cyclicNode1)
        )
        val indegree = mapOf(
            readyNode to 0,
            cyclicNode1 to 1,
            cyclicNode2 to 1
        )

        val result = sorter.sort(adjacency, indegree, listOf(readyNode, cyclicNode1, cyclicNode2))

        // Should process ready node and detect cycle (tests both success and cycle paths)
        assertEquals(listOf(readyNode), result.ordered) // One node processed successfully
        assertEquals(1, result.issues.size) // Cycle detected
        assertFalse(result.isValid) // Overall invalid due to cycle

        // Verify cycle detection for remaining nodes
        val issue = result.issues[0]
        assertTrue(issue.message.contains(cyclicNode1.toString()))
        assertTrue(issue.message.contains(cyclicNode2.toString()))
        assertFalse(issue.message.contains(readyNode.toString())) // Ready node not in cycle
    }

    @Test
    fun `sort handles dependent node not in working indegree map`() {
        val node1 = UUID.randomUUID()
        val node2 = UUID.randomUUID()
        val orphanNode = UUID.randomUUID()

        // Create scenario where adjacency references a node not in indegree map
        val adjacency = mapOf(
            node1 to setOf(node2, orphanNode), // orphanNode not in indegree map
            node2 to emptySet()
        )
        val indegree = mapOf(
            node1 to 0,
            node2 to 1
            // orphanNode intentionally missing
        )

        val result = sorter.sort(adjacency, indegree, listOf(node1, node2))

        // Should handle gracefully (line 60 null check)
        assertTrue(result.isValid)
        assertEquals(listOf(node1, node2), result.ordered)
    }

    @Test
    fun `sort produces identical results across multiple runs`() {
        val nodeA = UUID.randomUUID()
        val nodeB = UUID.randomUUID()
        val nodeC = UUID.randomUUID()
        val nodeD = UUID.randomUUID()
        val nodeE = UUID.randomUUID()

        // Complex DAG with multiple tie-breaking opportunities: A,B -> C,D -> E
        val adjacency = mapOf(
            nodeA to setOf(nodeC, nodeD),
            nodeB to setOf(nodeC, nodeD),
            nodeC to setOf(nodeE),
            nodeD to setOf(nodeE),
            nodeE to emptySet()
        )
        val indegree = mapOf(
            nodeA to 0,
            nodeB to 0,
            nodeC to 2,
            nodeD to 2,
            nodeE to 2
        )
        val declarationOrder = listOf(nodeB, nodeA, nodeD, nodeC, nodeE) // Non-alphabetical order

        // Run the sorter multiple times and verify results are identical
        val results = (1..5).map {
            sorter.sort(adjacency, indegree, declarationOrder)
        }

        // All results should be identical
        val firstResult = results[0]
        results.forEach { result ->
            assertEquals(firstResult.ordered, result.ordered, "Results should be identical across runs")
            assertEquals(firstResult.issues.size, result.issues.size, "Issue count should be identical")
            assertTrue(result.isValid, "All results should be valid")
        }

        // Verify expected ordering based on declaration order tie-breaking
        // nodeB and nodeA can start first (tie-broken by declaration order: B before A)
        // Then nodeD and nodeC (tie-broken by declaration order: D before C)
        // Finally nodeE
        assertTrue(firstResult.ordered.indexOf(nodeB) < firstResult.ordered.indexOf(nodeA))
        assertTrue(firstResult.ordered.indexOf(nodeD) < firstResult.ordered.indexOf(nodeC))
        assertTrue(firstResult.ordered.indexOf(nodeC) < firstResult.ordered.indexOf(nodeE))
        assertTrue(firstResult.ordered.indexOf(nodeD) < firstResult.ordered.indexOf(nodeE))
    }

    @Test
    fun `sort maintains ready queue ordering after adding newly ready nodes`() {
        val node1 = UUID.randomUUID()
        val node2 = UUID.randomUUID()
        val node3 = UUID.randomUUID()
        val node4 = UUID.randomUUID()

        // Create a scenario where multiple nodes become ready at different times
        // but need to be processed in declaration order
        val adjacency = mapOf(
            node1 to setOf(node3, node4), // node1 makes node3 and node4 ready
            node2 to emptySet(),
            node3 to emptySet(),
            node4 to emptySet()
        )
        val indegree = mapOf(
            node1 to 0,
            node2 to 0,
            node3 to 1, // Will become ready when node1 is processed
            node4 to 1 // Will become ready when node1 is processed
        )

        // Declaration order puts node4 before node3 (reverse alphabetical for testing)
        val result = sorter.sort(adjacency, indegree, listOf(node1, node4, node2, node3))

        assertTrue(result.isValid)
        assertEquals(4, result.ordered.size)

        // node1 and node2 start ready - node1 should come first per declaration order
        assertTrue(result.ordered.indexOf(node1) < result.ordered.indexOf(node2))

        // When node1 is processed, both node3 and node4 become ready
        // node4 should come before node3 per declaration order
        assertTrue(result.ordered.indexOf(node4) < result.ordered.indexOf(node3))

        // The expected order based on how the algorithm actually works:
        // 1. Initially ready: node1 (rank 0), node2 (rank 2) -> node1 first
        // 2. After processing node1: node2 (rank 2), node4 (rank 1), node3 (rank 3) -> node4 first
        // 3. After processing node4: node2 (rank 2), node3 (rank 3) -> node2 first
        // 4. Finally: node3
        assertEquals(listOf(node1, node4, node2, node3), result.ordered)
    }

    @Test
    fun `sort handles original indegree map immutability`() {
        val node1 = UUID.randomUUID()
        val node2 = UUID.randomUUID()

        val adjacency = mapOf(
            node1 to setOf(node2),
            node2 to emptySet()
        )
        val originalIndegree = mapOf(
            node1 to 0,
            node2 to 1
        )

        // Make a copy to verify original is not mutated
        val indegreeSnapshot = originalIndegree.toMap()

        val result = sorter.sort(adjacency, originalIndegree, listOf(node1, node2))

        // Verify original indegree map is unchanged
        assertEquals(indegreeSnapshot, originalIndegree, "Original indegree map should not be mutated")
        assertTrue(result.isValid)
        assertEquals(listOf(node1, node2), result.ordered)
    }

    @Test
    fun `sort cycle detection compares emitted vs total node count correctly`() {
        val node1 = UUID.randomUUID()
        val node2 = UUID.randomUUID()
        val node3 = UUID.randomUUID()

        // Two nodes in cycle, one independent
        val adjacency = mapOf(
            node1 to setOf(node2),
            node2 to setOf(node1), // cycle between node1 and node2
            node3 to emptySet() // independent node
        )
        val indegree = mapOf(
            node1 to 1,
            node2 to 1,
            node3 to 0 // can be processed
        )

        val result = sorter.sort(adjacency, indegree, listOf(node1, node2, node3))

        assertFalse(result.isValid)
        assertEquals(listOf(node3), result.ordered) // Only node3 processed
        assertEquals(1, result.issues.size)

        // Verify cycle detection correctly reports emitted vs total count
        val message = result.issues[0].message
        assertTrue(message.contains("Processed 1 of 3 nodes"), "Should show 1 processed out of 3 total")
        assertTrue(message.contains(node1.toString()), "Should list node1 in cycle")
        assertTrue(message.contains(node2.toString()), "Should list node2 in cycle")
        assertFalse(message.contains(node3.toString()), "Should not list processed node3 in cycle")
    }
}
