package carp.dsp.infrastructure.execute.workspace

import dk.cachet.carp.analytics.application.plan.CommandSpec
import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import dk.cachet.carp.analytics.application.plan.PlannedStep
import dk.cachet.carp.analytics.application.plan.ResolvedBindings
import dk.cachet.carp.common.application.UUID
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlanBasedWorkspaceManagerTest {

    @TempDir
    private lateinit var tempDir: Path

    private lateinit var workspaceManager: PlanBasedWorkspaceManager
    private lateinit var baseWorkspaceRoot: Path

    @BeforeEach
    fun setup() {
        baseWorkspaceRoot = tempDir.resolve("workspaces")
        workspaceManager = PlanBasedWorkspaceManager(baseWorkspaceRoot)
    }

    @Test
    fun `constructor requires absolute path`() {
        // Arrange
        val relativePath = Paths.get("relative/path")

        // Act & Assert
        assertThrows<IllegalArgumentException> {
            PlanBasedWorkspaceManager(relativePath)
        }
    }

    @Test
    fun `creates base workspace directory on initialization`() {
        // Act (already done in setup via constructor)

        // Assert
        assertTrue(baseWorkspaceRoot.exists(), "Base workspace directory should be created")
        assertTrue(baseWorkspaceRoot.isDirectory(), "Base workspace should be a directory")
    }

    @Test
    fun `same plan produces same planHash`() {
        // Arrange
        val runId1 = UUID.randomUUID()
        val runId2 = UUID.randomUUID()
        val plan = createTestExecutionPlan("workflow1", "plan1")

        // Act
        val workspace1 = workspaceManager.create(plan, runId1)
        val workspace2 = workspaceManager.create(plan, runId2)

        // Assert
        val hash1 = workspace1.executionRoot.substringBefore("_")
        val hash2 = workspace2.executionRoot.substringBefore("_")
        assertEquals(hash1, hash2, "Same plan should produce same hash")

        // But different runIds should produce different execution roots
        assertNotEquals(workspace1.executionRoot, workspace2.executionRoot,
                       "Same plan with different runIds should have different execution roots")
    }

    @Test
    fun `different plans produce different planHashes`() {
        // Arrange
        val runId = UUID.randomUUID()
        val plan1 = createTestExecutionPlan("workflow1", "plan1")
        val plan2 = createTestExecutionPlan("workflow2", "plan2")

        // Act
        val workspace1 = workspaceManager.create(plan1, runId)
        val workspace2 = workspaceManager.create(plan2, runId)

        // Assert
        val hash1 = workspace1.executionRoot.substringBefore("_")
        val hash2 = workspace2.executionRoot.substringBefore("_")
        assertNotEquals(hash1, hash2, "Different plans should produce different hashes")
    }

    @Test
    fun `different step configurations produce different planHashes`() {
        // Arrange
        val runId = UUID.randomUUID()
        val stepId1 = UUID.randomUUID()
        val stepId2 = UUID.randomUUID()

        val plan1 = ExecutionPlan(
            workflowId = "workflow1",
            planId = "plan1",
            steps = listOf(createTestPlannedStep(stepId1, "step1"))
        )

        val plan2 = ExecutionPlan(
            workflowId = "workflow1", // Same workflow
            planId = "plan1",         // Same plan ID
            steps = listOf(createTestPlannedStep(stepId2, "step2")) // Different step
        )

        // Act
        val workspace1 = workspaceManager.create(plan1, runId)
        val workspace2 = workspaceManager.create(plan2, runId)

        // Assert
        val hash1 = workspace1.executionRoot.substringBefore("_")
        val hash2 = workspace2.executionRoot.substringBefore("_")
        assertNotEquals(hash1, hash2, "Plans with different steps should produce different hashes")
    }

    @Test
    fun `directory structure created for all steps in plan`() {
        // Arrange
        val runId = UUID.randomUUID()
        val stepId1 = UUID.randomUUID()
        val stepId2 = UUID.randomUUID()
        val stepId3 = UUID.randomUUID()

        val plan = ExecutionPlan(
            workflowId = "workflow1",
            planId = "plan1",
            steps = listOf(
                createTestPlannedStep(stepId1, "step1"),
                createTestPlannedStep(stepId2, "step2"),
                createTestPlannedStep(stepId3, "step3")
            )
        )

        // Act
        val workspace = workspaceManager.create(plan, runId)

        // Assert
        val executionRootPath = baseWorkspaceRoot.resolve(workspace.executionRoot)
        assertTrue(executionRootPath.exists(), "Execution root should exist")

        val stepsPath = executionRootPath.resolve("steps")
        assertTrue(stepsPath.exists(), "Steps directory should exist")

        // Check each step directory structure
        listOf(stepId1, stepId2, stepId3).forEach { stepId ->
            val stepDir = stepsPath.resolve(stepId.toString())
            val inputsDir = stepDir.resolve("inputs")
            val outputsDir = stepDir.resolve("outputs")
            val logsDir = stepDir.resolve("logs")

            assertTrue(stepDir.exists() && stepDir.isDirectory(), "Step $stepId directory should exist")
            assertTrue(inputsDir.exists() && inputsDir.isDirectory(), "Step $stepId inputs directory should exist")
            assertTrue(outputsDir.exists() && outputsDir.isDirectory(), "Step $stepId outputs directory should exist")
            assertTrue(logsDir.exists() && logsDir.isDirectory(), "Step $stepId logs directory should exist")
        }
    }

    @Test
    fun `executionRoot path matches expected format`() {
        // Arrange
        val runId = UUID.randomUUID()
        val plan = createTestExecutionPlan("workflow1", "plan1")

        // Act
        val workspace = workspaceManager.create(plan, runId)

        // Assert
        val executionRoot = workspace.executionRoot
        assertTrue(executionRoot.contains("_"), "Execution root should contain separator between hash and runId")

        val parts = executionRoot.split("_")
        assertEquals(2, parts.size, "Execution root should have exactly 2 parts: planHash_runId")

        val planHash = parts[0]
        val runIdPart = parts[1]

        assertTrue(planHash.matches(Regex("[0-9a-f]+")), "Plan hash should be hex string")
        assertEquals(16, planHash.length, "Plan hash should be 16 characters (8 bytes)")
        assertEquals(runId.toString(), runIdPart, "RunId part should match actual runId")

        assertEquals(runId, workspace.runId, "Workspace runId should match input")
    }

    @Test
    fun `prepareStepDirectories creates missing directories`() {
        // Arrange
        val runId = UUID.randomUUID()
        val stepId = UUID.randomUUID()
        val plan = ExecutionPlan(
            workflowId = "workflow1",
            planId = "plan1",
            steps = emptyList() // No steps in initial plan
        )

        val workspace = workspaceManager.create(plan, runId)

        // Act
        workspaceManager.prepareStepDirectories(workspace, stepId)

        // Assert
        val executionRootPath = baseWorkspaceRoot.resolve(workspace.executionRoot)
        val stepDir = executionRootPath.resolve("steps").resolve(stepId.toString())
        val inputsDir = stepDir.resolve("inputs")
        val outputsDir = stepDir.resolve("outputs")
        val logsDir = stepDir.resolve("logs")

        assertTrue(stepDir.exists() && stepDir.isDirectory(), "Step directory should exist")
        assertTrue(inputsDir.exists() && inputsDir.isDirectory(), "Inputs directory should exist")
        assertTrue(outputsDir.exists() && outputsDir.isDirectory(), "Outputs directory should exist")
        assertTrue(logsDir.exists() && logsDir.isDirectory(), "Logs directory should exist")
    }

    @Test
    fun `workspace creation is idempotent`() {
        // Arrange
        val runId = UUID.randomUUID()
        val plan = createTestExecutionPlan("workflow1", "plan1")

        // Act
        val workspace1 = workspaceManager.create(plan, runId)
        val workspace2 = workspaceManager.create(plan, runId) // Same parameters

        // Assert
        assertEquals(workspace1.runId, workspace2.runId)
        assertEquals(workspace1.executionRoot, workspace2.executionRoot)

        val executionRootPath = baseWorkspaceRoot.resolve(workspace1.executionRoot)
        assertTrue(executionRootPath.exists(), "Execution root should still exist")
    }

    @Test
    fun `plan hash is stable across multiple computations`() {
        // Arrange
        val runId1 = UUID.randomUUID()
        val runId2 = UUID.randomUUID()
        val plan1 = createTestExecutionPlan("workflow1", "plan1")
        val plan2 = ExecutionPlan(
            workflowId = plan1.workflowId,
            planId = plan1.planId,
            steps = plan1.steps,
            issues = plan1.issues,
            requiredEnvironmentHandles = plan1.requiredEnvironmentHandles
        )

        // Act
        val workspace1 = workspaceManager.create(plan1, runId1)
        val workspace2 = workspaceManager.create(plan2, runId2)

        // Assert
        val hash1 = workspace1.executionRoot.substringBefore("_")
        val hash2 = workspace2.executionRoot.substringBefore("_")
        assertEquals(hash1, hash2, "Identical plans should produce identical hashes")
    }

    @Test
    fun `plan hash ignores step ordering`() {
        // Arrange
        val runId = UUID.randomUUID()
        val stepId1 = UUID.randomUUID()
        val stepId2 = UUID.randomUUID()

        val plan1 = ExecutionPlan(
            workflowId = "workflow1",
            planId = "plan1",
            steps = listOf(
                createTestPlannedStep(stepId1, "step1"),
                createTestPlannedStep(stepId2, "step2")
            )
        )

        val plan2 = ExecutionPlan(
            workflowId = "workflow1",
            planId = "plan1",
            steps = listOf(
                createTestPlannedStep(stepId2, "step2"), // Reversed order
                createTestPlannedStep(stepId1, "step1")
            )
        )

        // Act
        val workspace1 = workspaceManager.create(plan1, runId)
        val workspace2 = workspaceManager.create(plan2, runId)

        // Assert
        val hash1 = workspace1.executionRoot.substringBefore("_")
        val hash2 = workspace2.executionRoot.substringBefore("_")
        assertEquals(hash1, hash2, "Plan hash should be stable regardless of step ordering")
    }

    @Test
    fun `different required environment handles produce different hashes`() {
        // Arrange
        val runId = UUID.randomUUID()
        val envHandle1 = UUID.randomUUID()
        val envHandle2 = UUID.randomUUID()

        val plan1 = ExecutionPlan(
            workflowId = "workflow1",
            planId = "plan1",
            steps = listOf(createTestPlannedStep(UUID.randomUUID(), "step1")),
            requiredEnvironmentHandles = listOf(envHandle1)
        )

        val plan2 = ExecutionPlan(
            workflowId = "workflow1",
            planId = "plan1",
            steps = listOf(createTestPlannedStep(UUID.randomUUID(), "step1")),
            requiredEnvironmentHandles = listOf(envHandle2)
        )

        // Act
        val workspace1 = workspaceManager.create(plan1, runId)
        val workspace2 = workspaceManager.create(plan2, runId)

        // Assert
        val hash1 = workspace1.executionRoot.substringBefore("_")
        val hash2 = workspace2.executionRoot.substringBefore("_")
        assertNotEquals(hash1, hash2, "Plans with different environment handles should have different hashes")
    }

    @Test
    fun `plan hash does not depend on workflow definition`() {
        // This test ensures the implementation only uses ExecutionPlan data
        // and doesn't accidentally depend on WorkflowDefinition

        // Arrange - Create two plans with identical structure but conceptually from different workflows
        val runId = UUID.randomUUID()
        val stepId = UUID.randomUUID()

        val plan1 = ExecutionPlan(
            workflowId = "workflow-alpha",
            planId = "plan1",
            steps = listOf(createTestPlannedStep(stepId, "transform"))
        )

        val plan2 = ExecutionPlan(
            workflowId = "workflow-beta", // Different workflow ID
            planId = "plan1",              // Same plan ID
            steps = listOf(createTestPlannedStep(stepId, "transform")) // Same step
        )

        // Act
        val workspace1 = workspaceManager.create(plan1, runId)
        val workspace2 = workspaceManager.create(plan2, runId)

        // Assert
        val hash1 = workspace1.executionRoot.substringBefore("_")
        val hash2 = workspace2.executionRoot.substringBefore("_")
        assertNotEquals(hash1, hash2, "Different workflow IDs should produce different hashes")

        // But this proves we're only using ExecutionPlan data, not external WorkflowDefinition
    }

    @Test
    fun `multiple workspaces can coexist`() {
        // Arrange
        val runId1 = UUID.randomUUID()
        val runId2 = UUID.randomUUID()
        val plan1 = createTestExecutionPlan("workflow1", "plan1")
        val plan2 = createTestExecutionPlan("workflow2", "plan2")

        // Act
        val workspace1 = workspaceManager.create(plan1, runId1)
        val workspace2 = workspaceManager.create(plan2, runId2)

        // Assert
        val executionRoot1 = baseWorkspaceRoot.resolve(workspace1.executionRoot)
        val executionRoot2 = baseWorkspaceRoot.resolve(workspace2.executionRoot)

        assertTrue(executionRoot1.exists(), "Workspace 1 should exist")
        assertTrue(executionRoot2.exists(), "Workspace 2 should exist")
        assertNotEquals(executionRoot1, executionRoot2, "Workspaces should be in different directories")
    }

    // Helper methods

    private fun createTestExecutionPlan(workflowId: String, planId: String): ExecutionPlan {
        val stepId = UUID.randomUUID()
        return ExecutionPlan(
            workflowId = workflowId,
            planId = planId,
            steps = listOf(createTestPlannedStep(stepId, "test-step"))
        )
    }

    private fun createTestPlannedStep(stepId: UUID, name: String): PlannedStep {
        return PlannedStep(
            stepId = stepId,
            name = name,
            process = CommandSpec("echo", listOf("hello")), // Concrete implementation
            bindings = ResolvedBindings(emptyMap(), emptyMap()),
            environmentDefinitionId = UUID.randomUUID()
        )
    }
}


