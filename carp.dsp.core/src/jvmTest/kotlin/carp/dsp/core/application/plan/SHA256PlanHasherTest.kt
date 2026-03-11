package carp.dsp.core.application.plan

import junit.framework.TestCase.assertTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SHA256PlanHasherTest {
    private val hasher = SHA256PlanHasher()

    @Test
    fun same_plan_produces_same_hash() {
        val plan = createTestExecutionPlan()
        val hash1 = hasher.hash(plan)
        val hash2 = hasher.hash(plan)

        assertEquals(hash1, hash2)
    }

    @Test
    fun different_plans_produce_different_hashes() {
        val plan1 = createTestExecutionPlan(stepCount = 1)
        val plan2 = createTestExecutionPlan(stepCount = 2)

        val hash1 = hasher.hash(plan1)
        val hash2 = hasher.hash(plan2)

        assertNotEquals(hash1, hash2)
    }

    @Test
    fun hash_is_deterministic() {
        val plan = createTestExecutionPlan()
        val hashes = (1..5).map { hasher.hash(plan) }

        assertTrue(hashes.all { it == hashes[0] })
    }

    @Test
    fun hash_is_hex_encoded_string() {
        val plan = createTestExecutionPlan()
        val hash = hasher.hash(plan)

        assertTrue(hash.matches(Regex("[0-9a-f]{64}"))) // SHA-256 is 64 hex chars
    }

    @Test
    fun hash_includes_all_plan_elements() {
        val plan1 = createTestExecutionPlan(
            workflowId = "workflow-1"
        )
        val plan2 = createTestExecutionPlan(
            workflowId = "workflow-2"
        )

        assertNotEquals(hasher.hash(plan1), hasher.hash(plan2))
    }
}
