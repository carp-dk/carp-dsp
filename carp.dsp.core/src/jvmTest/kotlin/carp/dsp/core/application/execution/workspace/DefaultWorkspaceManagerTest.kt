@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package carp.dsp.core.application.execution.workspace

import dk.cachet.carp.analytics.application.plan.CommandSpec
import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import dk.cachet.carp.analytics.application.plan.PlannedStep
import dk.cachet.carp.analytics.application.plan.ResolvedBindings
import dk.cachet.carp.common.application.UUID
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DefaultWorkspaceManagerTest {

    private lateinit var tempDir: Path
    private lateinit var workspaceManager: DefaultWorkspaceManager
    private lateinit var baseWorkspaceRoot: Path

    @BeforeTest
    fun setup() {
        // Create temporary directory manually
        tempDir = Files.createTempDirectory("workspace-test")
        baseWorkspaceRoot = tempDir.resolve("workspaces")
        workspaceManager = DefaultWorkspaceManager(baseWorkspaceRoot)
    }

    @AfterTest
    fun cleanup() {
        // Clean up temporary directory
        tempDir.deleteRecursively()
    }

    @Test
    fun `creates base workspace directory on initialization`() {
        // Act (already done in setup via constructor)

        // Assert
        assertTrue(baseWorkspaceRoot.exists(), "Base workspace directory should be created")
        assertTrue(baseWorkspaceRoot.isDirectory(), "Base workspace should be a directory")
    }

    @Test
    fun `constructor requires absolute path`() {
        // Arrange
        val relativePath = Paths.get("relative/path")

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            DefaultWorkspaceManager(relativePath)
        }
    }

    @Test
    fun `create method creates execution root directory`() {
        // Arrange
        val runId = UUID.randomUUID()
        val plan = createTestExecutionPlan()

        // Act
        val workspace = workspaceManager.create(plan, runId)

        // Assert
        assertEquals(runId, workspace.runId)
        assertEquals(runId.toString(), workspace.executionRoot)

        val executionRootPath = baseWorkspaceRoot.resolve(runId.toString())
        assertTrue(executionRootPath.exists(), "Execution root directory should be created")
        assertTrue(executionRootPath.isDirectory(), "Execution root should be a directory")
    }

    @Test
    fun `create method creates steps directory`() {
        // Arrange
        val runId = UUID.randomUUID()
        val plan = createTestExecutionPlan()

        // Act
        workspaceManager.create(plan, runId)

        // Assert
        val stepsPath = baseWorkspaceRoot.resolve(runId.toString()).resolve("steps")
        assertTrue(stepsPath.exists(), "Steps directory should be created")
        assertTrue(stepsPath.isDirectory(), "Steps directory should be a directory")
    }

    @Test
    fun `prepareStepDirectories creates correct directory structure`() {
        // Arrange
        val runId = UUID.randomUUID()
        val stepId = UUID.randomUUID()
        val plan = createTestExecutionPlan()
        val workspace = workspaceManager.create(plan, runId)

        // Act
        workspaceManager.prepareStepDirectories(workspace, stepId)

        // Assert
        val executionRoot = baseWorkspaceRoot.resolve(runId.toString())
        val stepDir = executionRoot.resolve("steps").resolve(stepId.toString())
        val inputsDir = stepDir.resolve("inputs")
        val outputsDir = stepDir.resolve("outputs")
        val logsDir = stepDir.resolve("logs")

        assertTrue(stepDir.exists() && stepDir.isDirectory(), "Step directory should exist")
        assertTrue(inputsDir.exists() && inputsDir.isDirectory(), "Inputs directory should exist")
        assertTrue(outputsDir.exists() && outputsDir.isDirectory(), "Outputs directory should exist")
        assertTrue(logsDir.exists() && logsDir.isDirectory(), "Logs directory should exist")
    }

    @Test
    fun `resolveArtifactPath resolves relative path under execution root`() {
        // Arrange
        val runId = UUID.randomUUID()
        val plan = createTestExecutionPlan()
        val workspace = workspaceManager.create(plan, runId)
        val relativePath = "data/input.txt"

        // Act
        val resolvedPath = workspaceManager.resolveArtifactPath(workspace, relativePath)

        // Assert
        val expectedPath = baseWorkspaceRoot.resolve(runId.toString()).resolve(relativePath)
        assertEquals(expectedPath.normalize(), resolvedPath.normalize())
        assertTrue(
            resolvedPath.startsWith(baseWorkspaceRoot.resolve(runId.toString())),
                  "Resolved path should be under execution root"
        )
    }

    @Test
    fun `resolveArtifactPath rejects traversal attempts`() {
        // Arrange
        val runId = UUID.randomUUID()
        val plan = createTestExecutionPlan()
        val workspace = workspaceManager.create(plan, runId)
        val traversalPath = "../../../etc/passwd"

        // Act & Assert
        assertFailsWith<SecurityException> {
            workspaceManager.resolveArtifactPath(workspace, traversalPath)
        }
    }

    @Test
    fun `resolveStepOutputArtifact accepts paths within step outputs directory`() {
        // Arrange
        val runId = UUID.randomUUID()
        val stepId = UUID.randomUUID()
        val plan = createTestExecutionPlan()
        val workspace = workspaceManager.create(plan, runId)
        workspaceManager.prepareStepDirectories(workspace, stepId)

        val validOutputPath = "steps/$stepId/outputs/result.txt"

        // Act
        val resolvedPath = workspaceManager.resolveStepOutputArtifact(workspace, stepId, validOutputPath)

        // Assert
        val expectedPath = baseWorkspaceRoot.resolve(runId.toString()).resolve(validOutputPath)
        assertEquals(expectedPath.normalize(), resolvedPath.normalize())

        // Verify it's within the step's outputs directory
        val outputsDir = baseWorkspaceRoot.resolve(runId.toString()).resolve("steps").resolve(stepId.toString()).resolve("outputs")
        assertTrue(
            resolvedPath.startsWith(outputsDir.normalize()),
                  "Resolved path should be within step outputs directory"
        )
    }

    @Test
    fun `resolveStepOutputArtifact rejects paths outside step outputs directory`() {
        // Arrange
        val runId = UUID.randomUUID()
        val stepId = UUID.randomUUID()
        val plan = createTestExecutionPlan()
        val workspace = workspaceManager.create(plan, runId)
        workspaceManager.prepareStepDirectories(workspace, stepId)

        val invalidOutputPath = "steps/$stepId/inputs/input.txt" // Wrong subdirectory

        // Act & Assert
        assertFailsWith<IllegalArgumentException> {
            workspaceManager.resolveStepOutputArtifact(workspace, stepId, invalidOutputPath)
        }
    }

    @Test
    fun `getStepWorkingDirectory returns correct absolute path`() {
        // Arrange
        val runId = UUID.randomUUID()
        val stepId = UUID.randomUUID()
        val plan = createTestExecutionPlan()
        val workspace = workspaceManager.create(plan, runId)
        workspaceManager.prepareStepDirectories(workspace, stepId)

        // Act
        val stepWorkingDir = workspaceManager.resolveStepWorkingDir(workspace, stepId)

        // Assert
        val expectedPath = baseWorkspaceRoot.resolve(runId.toString()).resolve("steps").resolve(stepId.toString())
        assertEquals(expectedPath.normalize().toString(), stepWorkingDir)
        val resolvedPath = Path.of(checkNotNull(stepWorkingDir))
        assertTrue(resolvedPath.isAbsolute, "Step working directory should be absolute")
        assertTrue(
            resolvedPath.exists() && resolvedPath.isDirectory(),
            "Step working directory should exist and be a directory"
        )
    }

    // Helper methods for creating test objects

    private fun createTestExecutionPlan(workflowId: String = "test-workflow", planId: String = "test-plan"): ExecutionPlan {
        val stepId = UUID.randomUUID()
        return ExecutionPlan(
            workflowId = workflowId,
            planId = planId,
            steps = listOf(createTestPlannedStep(stepId))
        )
    }

    private fun createTestPlannedStep(stepId: UUID, name: String = "test-step"): PlannedStep {
        return PlannedStep(
            stepId = stepId,
            name = name,
            process = CommandSpec("echo", listOf("hello")),
            bindings = ResolvedBindings(emptyMap(), emptyMap()),
            environmentDefinitionId = UUID.randomUUID()
        )
    }
}
