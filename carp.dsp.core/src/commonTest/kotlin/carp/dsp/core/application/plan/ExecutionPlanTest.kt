package carp.dsp.core.application.plan

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExecutionPlanTest {

    private val json = Json {
        encodeDefaults = true
    classDiscriminator = "type"
    }

    private fun step(id: String): PlannedStep =
        PlannedStep(
            stepId = id,
            name = "Step $id",
            process = InProcessRun(operationId = "op-$id"),
            bindings = ResolvedBindings()
        )

    @Test
    fun `validate passes for minimal valid plan`() {
        val plan = ExecutionPlan(
            workflowId = "wf",
            planId = "plan",
            steps = listOf(step("s1")),
            issues = emptyList(),
            requiredEnvironmentHandles = emptyList()
        )

        plan.validate() // should not throw
    }

    @Test
    fun `validate rejects blank workflowId`() {
        val plan = ExecutionPlan(
            workflowId = " ",
            planId = "plan",
            steps = listOf(step("s1"))
        )
        assertFailsWith<IllegalArgumentException> { plan.validate() }
    }

    @Test
    fun `validate rejects blank planId`() {
        val plan = ExecutionPlan(
            workflowId = "wf",
            planId = " ",
            steps = listOf(step("s1"))
        )
        assertFailsWith<IllegalArgumentException> { plan.validate() }
    }

    @Test
    fun `validate rejects duplicate stepIds`() {
        val plan = ExecutionPlan(
            workflowId = "wf",
            planId = "plan",
            steps = listOf(step("dup"), step("dup"))
        )
        assertFailsWith<IllegalArgumentException> { plan.validate() }
    }

    @Test
    fun `validate rejects duplicate environment handle ids`() {
        val plan = ExecutionPlan(
            workflowId = "wf",
            planId = "plan",
            steps = listOf(step("s1")),
            requiredEnvironmentHandles = listOf(
                EnvironmentHandleRef("h1"),
                EnvironmentHandleRef("h1"),
            )
        )
        assertFailsWith<IllegalArgumentException> { plan.validate() }
    }

    @Test
    fun `hasErrors true when any ERROR issue exists`() {
        val plan = ExecutionPlan(
            workflowId = "wf",
            planId = "plan",
            steps = listOf(step("s1")),
            issues = listOf(
                PlanIssue(severity = PlanIssueSeverity.INFO, code = "I", message = "info"),
                PlanIssue(severity = PlanIssueSeverity.ERROR, code = "E", message = "bad"),
            )
        )

        assertTrue(plan.hasErrors())
        assertFalse(plan.isRunnable())
    }

    @Test
    fun `hasErrors false when only INFO and WARNING issues exist`() {
        val plan = ExecutionPlan(
            workflowId = "wf",
            planId = "plan",
            steps = listOf(step("s1")),
            issues = listOf(
                PlanIssue(severity = PlanIssueSeverity.INFO, code = "I", message = "info"),
                PlanIssue(severity = PlanIssueSeverity.WARNING, code = "W", message = "warn"),
            )
        )

        assertFalse(plan.hasErrors())
        assertTrue(plan.isRunnable())
    }

    @Test
    fun `round-trip serialization`() {
        val plan = ExecutionPlan(
            workflowId = "wf",
            planId = "plan",
            steps = listOf(step("s1")),
            issues = listOf(
                PlanIssue(severity = PlanIssueSeverity.WARNING, code = "W1", message = "warn", stepId = "s1")
            ),
            requiredEnvironmentHandles = listOf(EnvironmentHandleRef("h1"))
        )

        val encoded = json.encodeToString(plan)
        val decoded = json.decodeFromString<ExecutionPlan>(encoded)

        assertEquals(plan, decoded)
    }
}
