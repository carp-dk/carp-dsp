package carp.dsp.core.infrastructure.execution

import dk.cachet.carp.analytics.application.plan.CommandSpec
import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import dk.cachet.carp.analytics.application.plan.PlannedStep
import dk.cachet.carp.analytics.application.plan.ResolvedBindings
import dk.cachet.carp.analytics.infrastructure.serialization.CoreAnalyticsSerializer
import dk.cachet.carp.common.application.UUID
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.test.*

/**
 * Integration tests for workspace layout stability and filesystem materialization.
 *
 * These tests verify that:
 * 1. Identical ExecutionPlans produce identical workspace layouts
 * 2. Plan hash is stable across serialization/deserialization
 * 3. RunId only affects the run root, not relative step layouts
 * 4. Directory structures are correctly materialized on disk
 */
class WorkspaceLayoutIntegrationTest {

    private lateinit var tempDir: Path
    private lateinit var baseWorkspaceRoot: Path
    private lateinit var workspaceManager: PlanBasedWorkspaceManager

    // Fixed UUIDs for deterministic testing
    private val fixedStepId1 = UUID.parse("550e8400-e29b-41d4-a716-446655440001")
    private val fixedStepId2 = UUID.parse("550e8400-e29b-41d4-a716-446655440002")
    private val fixedStepId3 = UUID.parse("550e8400-e29b-41d4-a716-446655440003")
    private val fixedEnvId1 = UUID.parse("6ba7b810-9dad-11d1-80b4-00c04fd430c8")
    private val fixedEnvId2 = UUID.parse("6ba7b811-9dad-11d1-80b4-00c04fd430c8")
    private val fixedRunId1 = UUID.parse("f47ac10b-58cc-4372-a567-0e02b2c3d479")
    private val fixedRunId2 = UUID.parse("f47ac10b-58cc-4372-a567-0e02b2c3d480")

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("workspace-integration-test")
        baseWorkspaceRoot = tempDir.resolve("workspaces")
        workspaceManager = PlanBasedWorkspaceManager(baseWorkspaceRoot)
    }

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `same plan produces identical relative layout`() {
        // Arrange
        val plan = createPlanA()

        // Act
        val ws1 = workspaceManager.create(plan, fixedRunId1)
        val ws2 = workspaceManager.create(plan, fixedRunId2)

        // Assert: Plan hash portion should be identical
        val planHash1 = ws1.executionRoot.substringBefore("_")
        val planHash2 = ws2.executionRoot.substringBefore("_")
        assertEquals(planHash1, planHash2, "Same plan should produce identical plan hash")

        // Assert: Relative step layout should be identical
        val stepIds = listOf(fixedStepId1, fixedStepId2, fixedStepId3)
        for (stepId in stepIds) {
            assertEquals(
                ws1.stepDir(stepId), ws2.stepDir(stepId),
                "Step directory path should be identical for stepId $stepId"
            )
            assertEquals(
                ws1.stepInputsDir(stepId), ws2.stepInputsDir(stepId),
                "Step inputs directory path should be identical for stepId $stepId"
            )
            assertEquals(
                ws1.stepOutputsDir(stepId), ws2.stepOutputsDir(stepId),
                "Step outputs directory path should be identical for stepId $stepId"
            )
            assertEquals(
                ws1.stepLogsDir(stepId), ws2.stepLogsDir(stepId),
                "Step logs directory path should be identical for stepId $stepId"
            )
        }
    }

    @Test
    fun `step directories and standard subdirs exist on filesystem`() {
        // Arrange
        val plan = createPlanA()

        // Act
        val workspace = workspaceManager.create(plan, fixedRunId1)

        // Assert: Execution root directory exists
        val executionRoot = baseWorkspaceRoot.resolve(workspace.executionRoot)
        assertTrue(executionRoot.exists(), "Execution root directory should exist")
        assertTrue(executionRoot.isDirectory(), "Execution root should be a directory")

        // Assert: Step directories and subdirectories exist for all steps
        val stepIds = listOf(fixedStepId1, fixedStepId2, fixedStepId3)
        for (stepId in stepIds) {
            val stepDir = executionRoot.resolve(workspace.stepDir(stepId))
            val inputsDir = executionRoot.resolve(workspace.stepInputsDir(stepId))
            val outputsDir = executionRoot.resolve(workspace.stepOutputsDir(stepId))
            val logsDir = executionRoot.resolve(workspace.stepLogsDir(stepId))

            assertTrue(stepDir.exists(), "Step directory should exist for stepId $stepId")
            assertTrue(stepDir.isDirectory(), "Step directory should be a directory for stepId $stepId")

            assertTrue(inputsDir.exists(), "Inputs directory should exist for stepId $stepId")
            assertTrue(inputsDir.isDirectory(), "Inputs directory should be a directory for stepId $stepId")

            assertTrue(outputsDir.exists(), "Outputs directory should exist for stepId $stepId")
            assertTrue(outputsDir.isDirectory(), "Outputs directory should be a directory for stepId $stepId")

            assertTrue(logsDir.exists(), "Logs directory should exist for stepId $stepId")
            assertTrue(logsDir.isDirectory(), "Logs directory should be a directory for stepId $stepId")
        }
    }

    @Test
    fun `plan hash stable across serialization and deserialization`() {
        // Arrange
        val plan1 = createPlanA()

        // Act: Serialize and deserialize the plan
        val serializedPlan = CoreAnalyticsSerializer.json.encodeToString(ExecutionPlan.serializer(), plan1)
        val plan2 = CoreAnalyticsSerializer.json.decodeFromString(ExecutionPlan.serializer(), serializedPlan)

        // Create workspaces from original and deserialized plans
        val ws1 = workspaceManager.create(plan1, fixedRunId1)
        val ws2 = workspaceManager.create(plan2, fixedRunId2)

        // Assert: Plan hash should be identical despite serialization roundtrip
        val planHash1 = ws1.executionRoot.substringBefore("_")
        val planHash2 = ws2.executionRoot.substringBefore("_")
        assertEquals(
            planHash1, planHash2,
            "Plan hash should be stable across serialization/deserialization"
        )

        // Assert: Deserialized plan should equal original plan
        assertEquals(
            plan1, plan2,
            "Plan should roundtrip through serialization unchanged"
        )
    }

    @Test
    fun `runId only affects run root, not relative step layout`() {
        // Arrange
        val plan = createPlanA()

        // Act
        val ws1 = workspaceManager.create(plan, fixedRunId1)
        val ws2 = workspaceManager.create(plan, fixedRunId2)

        // Assert: Execution roots should be different (due to different runIds)
        assertNotEquals(
            ws1.executionRoot, ws2.executionRoot,
            "Different runIds should produce different execution roots"
        )

        // Assert: Plan hash portion should be identical
        val planHash1 = ws1.executionRoot.substringBefore("_")
        val planHash2 = ws2.executionRoot.substringBefore("_")
        assertEquals(
            planHash1, planHash2,
            "Plan hash should be identical regardless of runId"
        )

        // Assert: Relative step layouts should be identical
        val stepIds = listOf(fixedStepId1, fixedStepId2, fixedStepId3)
        for (stepId in stepIds) {
            assertEquals(
                ws1.stepDir(stepId), ws2.stepDir(stepId),
                "Step directories should have identical relative paths"
            )
            assertEquals(
                ws1.stepInputsDir(stepId), ws2.stepInputsDir(stepId),
                "Step inputs directories should have identical relative paths"
            )
            assertEquals(
                ws1.stepOutputsDir(stepId), ws2.stepOutputsDir(stepId),
                "Step outputs directories should have identical relative paths"
            )
            assertEquals(
                ws1.stepLogsDir(stepId), ws2.stepLogsDir(stepId),
                "Step logs directories should have identical relative paths"
            )
        }
    }

    @Test
    fun `different plans produce different plan hashes`() {
        // Arrange
        val planA = createPlanA()
        val planB = createPlanB()

        // Act
        val wsA = workspaceManager.create(planA, fixedRunId1)
        val wsB = workspaceManager.create(planB, fixedRunId1)

        // Assert: Plan hashes should be different
        val planHashA = wsA.executionRoot.substringBefore("_")
        val planHashB = wsB.executionRoot.substringBefore("_")
        assertNotEquals(
            planHashA, planHashB,
            "Different plans should produce different plan hashes"
        )
    }

    // Test fixtures and helper methods

    /**
     * Creates a fixed ExecutionPlan with deterministic structure for testing.
     * Contains 3 steps with fixed UUIDs and environment handles.
     */
    private fun createPlanA(): ExecutionPlan {
        return ExecutionPlan(
            workflowId = "test-workflow-a",
            planId = "plan-a-fixed",
            steps = listOf(
                PlannedStep(
                    stepId = fixedStepId1,
                    name = "data-ingestion",
                    process = CommandSpec("python", listOf("ingest.py", "--source", "sensor")),
                    bindings = ResolvedBindings(emptyMap(), emptyMap()),
                    environmentDefinitionId = fixedEnvId1
                ),
                PlannedStep(
                    stepId = fixedStepId2,
                    name = "data-transform",
                    process = CommandSpec("python", listOf("transform.py", "--algorithm", "fft")),
                    bindings = ResolvedBindings(emptyMap(), emptyMap()),
                    environmentDefinitionId = fixedEnvId1
                ),
                PlannedStep(
                    stepId = fixedStepId3,
                    name = "analysis-output",
                    process = CommandSpec("python", listOf("analyze.py", "--format", "json")),
                    bindings = ResolvedBindings(emptyMap(), emptyMap()),
                    environmentDefinitionId = fixedEnvId2
                )
            ),
            requiredEnvironmentHandles = listOf(fixedEnvId1, fixedEnvId2)
        )
    }

    /**
     * Creates a different ExecutionPlan with different structure for testing.
     */
    private fun createPlanB(): ExecutionPlan {
        return ExecutionPlan(
            workflowId = "test-workflow-b",
            planId = "plan-b-fixed",
            steps = listOf(
                PlannedStep(
                    stepId = fixedStepId1,
                    name = "data-processing",
                    process = CommandSpec("R", listOf("process.R", "--method", "linear")),
                    bindings = ResolvedBindings(emptyMap(), emptyMap()),
                    environmentDefinitionId = fixedEnvId2
                ),
                PlannedStep(
                    stepId = fixedStepId2,
                    name = "visualization",
                    process = CommandSpec("python", listOf("plot.py", "--type", "scatter")),
                    bindings = ResolvedBindings(emptyMap(), emptyMap()),
                    environmentDefinitionId = fixedEnvId1
                )
            ),
            requiredEnvironmentHandles = listOf(fixedEnvId1, fixedEnvId2)
        )
    }
}
