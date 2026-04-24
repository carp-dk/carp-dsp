package carp.dsp.core.application.plan

import junit.framework.TestCase.assertTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class IntegrationTest {
    private val mockHasher = MockPlanHasher("integration-test-hash") // Use mock (MPP-safe)

    @Test
    fun full_planning_produces_valid_diagnostics() {
        val definition = createTestWorkflowDefinition()
        val planner = DefaultExecutionPlanner()

        val plan = planner.plan(definition)
        val diag = plan.diagnostics(mockHasher) // ✅ Pass mock hasher

        assertNotNull(diag)
        assertTrue(diag.stepCount > 0)
        assertNotNull(diag.planHash)
    }

    @Test
    fun plan_diagnostics_include_hash() {
        val definition = createTestWorkflowDefinition()
        val planner = DefaultExecutionPlanner()

        val plan = planner.plan(definition)
        val diag = plan.diagnostics(mockHasher) // ✅ Pass mock hasher

        // Hash should be computed and included
        assertTrue(diag.planHash.isNotEmpty())
        assertEquals("integration-test-hash", diag.planHash)
    }

    @Test
    fun different_hasher_implementations_can_be_swapped() {
        val definition = createTestWorkflowDefinition()
        val planner = DefaultExecutionPlanner()
        val plan = planner.plan(definition)

        // Different hasher implementations can be used
        val deterministicHasher = DeterministicPlanHasher()
        val diag = plan.diagnostics(deterministicHasher)

        // Hashers can be swapped without changing code
        assertNotNull(diag.planHash)
        assertTrue(diag.planHash.isNotEmpty())
    }
}
