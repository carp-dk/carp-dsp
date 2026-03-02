package carp.dsp.core.application.environment.workspace

import dk.cachet.carp.common.application.UUID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultWorkspaceManagerTest {

    @TempDir
    private lateinit var tempDir: Path

    private lateinit var workspaceManager: DefaultWorkspaceManager
    private lateinit var baseWorkspaceRoot: Path

    @BeforeEach
    fun setup() {
        baseWorkspaceRoot = tempDir.resolve("workspaces")
        workspaceManager = DefaultWorkspaceManager(baseWorkspaceRoot)
    }

    @AfterEach
    fun cleanup() {
        // JUnit @TempDir handles cleanup automatically
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
        assertThrows<IllegalArgumentException> {
            DefaultWorkspaceManager(relativePath)
        }
    }

    @Test
    fun `createWorkspace creates execution root directory`() {
        // Arrange
        val runId = UUID.randomUUID()

        // Act
        val workspace = workspaceManager.createWorkspace(runId)

        // Assert
        assertEquals(runId, workspace.runId)
        assertEquals(runId.toString(), workspace.executionRoot)

        val executionRootPath = baseWorkspaceRoot.resolve(runId.toString())
        assertTrue(executionRootPath.exists(), "Execution root directory should be created")
        assertTrue(executionRootPath.isDirectory(), "Execution root should be a directory")
    }

    @Test
    fun `createWorkspace creates steps directory`() {
        // Arrange
        val runId = UUID.randomUUID()

        // Act
        workspaceManager.createWorkspace(runId)

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
        val workspace = workspaceManager.createWorkspace(runId)

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
        val workspace = workspaceManager.createWorkspace(runId)
        val relativePath = "data/input.txt"

        // Act
        val resolvedPath = workspaceManager.resolveArtifactPath(workspace, relativePath)

        // Assert
        val expectedPath = baseWorkspaceRoot.resolve(runId.toString()).resolve(relativePath)
        assertEquals(expectedPath.normalize(), resolvedPath.normalize())
        assertTrue(resolvedPath.startsWith(baseWorkspaceRoot.resolve(runId.toString())),
                  "Resolved path should be under execution root")
    }

    @Test
    fun `resolveArtifactPath rejects traversal attempts`() {
        // Arrange
        val runId = UUID.randomUUID()
        val workspace = workspaceManager.createWorkspace(runId)
        val traversalPath = "../../../etc/passwd"

        // Act & Assert
        assertThrows<SecurityException> {
            workspaceManager.resolveArtifactPath(workspace, traversalPath)
        }
    }

    @Test
    fun `resolveArtifactPath rejects absolute paths`() {
        // Arrange
        val runId = UUID.randomUUID()
        val workspace = workspaceManager.createWorkspace(runId)
        val absolutePath = "/etc/passwd"

        // Act & Assert
        assertThrows<IllegalArgumentException> {
            workspaceManager.resolveArtifactPath(workspace, absolutePath)
        }
    }

    @Test
    fun `resolveArtifactPath normalizes complex relative paths safely`() {
        // Arrange
        val runId = UUID.randomUUID()
        val workspace = workspaceManager.createWorkspace(runId)
        val complexPath = "dir1/../dir2/./file.txt"

        // Act
        val resolvedPath = workspaceManager.resolveArtifactPath(workspace, complexPath)

        // Assert
        val expectedPath = baseWorkspaceRoot.resolve(runId.toString()).resolve("dir2/file.txt")
        assertEquals(expectedPath.normalize(), resolvedPath.normalize())
        assertTrue(resolvedPath.startsWith(baseWorkspaceRoot.resolve(runId.toString())),
                  "Resolved path should be under execution root")
    }

    @Test
    fun `getStepWorkingDirectory returns correct absolute path`() {
        // Arrange
        val runId = UUID.randomUUID()
        val stepId = UUID.randomUUID()
        val workspace = workspaceManager.createWorkspace(runId)
        workspaceManager.prepareStepDirectories(workspace, stepId)

        // Act
        val stepWorkingDir = workspaceManager.getStepWorkingDirectory(workspace, stepId)

        // Assert
        val expectedPath = baseWorkspaceRoot.resolve(runId.toString()).resolve("steps").resolve(stepId.toString())
        assertEquals(expectedPath.normalize(), stepWorkingDir.normalize())
        assertTrue(stepWorkingDir.isAbsolute, "Step working directory should be absolute")
        assertTrue(stepWorkingDir.exists() && stepWorkingDir.isDirectory(),
                  "Step working directory should exist and be a directory")
    }

    @Test
    fun `multiple workspaces can be created without interference`() {
        // Arrange
        val runId1 = UUID.randomUUID()
        val runId2 = UUID.randomUUID()
        val stepId1 = UUID.randomUUID()
        val stepId2 = UUID.randomUUID()

        // Act
        val workspace1 = workspaceManager.createWorkspace(runId1)
        val workspace2 = workspaceManager.createWorkspace(runId2)
        workspaceManager.prepareStepDirectories(workspace1, stepId1)
        workspaceManager.prepareStepDirectories(workspace2, stepId2)

        // Assert
        val root1 = baseWorkspaceRoot.resolve(runId1.toString())
        val root2 = baseWorkspaceRoot.resolve(runId2.toString())
        val step1Dir = root1.resolve("steps").resolve(stepId1.toString())
        val step2Dir = root2.resolve("steps").resolve(stepId2.toString())

        assertTrue(root1.exists() && root1.isDirectory(), "Workspace 1 root should exist")
        assertTrue(root2.exists() && root2.isDirectory(), "Workspace 2 root should exist")
        assertTrue(step1Dir.exists() && step1Dir.isDirectory(), "Step 1 directory should exist")
        assertTrue(step2Dir.exists() && step2Dir.isDirectory(), "Step 2 directory should exist")

        // Ensure they are separate
        assertFalse(step1Dir.startsWith(step2Dir), "Step directories should be independent")
        assertFalse(step2Dir.startsWith(step1Dir), "Step directories should be independent")
    }

    @Test
    fun `workspace creation is idempotent`() {
        // Arrange
        val runId = UUID.randomUUID()

        // Act
        val workspace1 = workspaceManager.createWorkspace(runId)
        val workspace2 = workspaceManager.createWorkspace(runId) // Same runId

        // Assert
        assertEquals(workspace1.runId, workspace2.runId)
        assertEquals(workspace1.executionRoot, workspace2.executionRoot)

        val executionRootPath = baseWorkspaceRoot.resolve(runId.toString())
        assertTrue(executionRootPath.exists(), "Execution root should still exist")
    }

    @Test
    fun `step directory preparation is idempotent`() {
        // Arrange
        val runId = UUID.randomUUID()
        val stepId = UUID.randomUUID()
        val workspace = workspaceManager.createWorkspace(runId)

        // Act
        workspaceManager.prepareStepDirectories(workspace, stepId)
        workspaceManager.prepareStepDirectories(workspace, stepId) // Same stepId

        // Assert - should not throw and directories should still exist
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
    fun `path resolution handles nested directories correctly`() {
        // Arrange
        val runId = UUID.randomUUID()
        val workspace = workspaceManager.createWorkspace(runId)
        val nestedPath = "level1/level2/level3/file.txt"

        // Act
        val resolvedPath = workspaceManager.resolveArtifactPath(workspace, nestedPath)

        // Assert
        val expectedPath = baseWorkspaceRoot.resolve(runId.toString()).resolve(nestedPath)
        assertEquals(expectedPath.normalize(), resolvedPath.normalize())
        assertTrue(resolvedPath.startsWith(baseWorkspaceRoot.resolve(runId.toString())),
                  "Resolved nested path should be under execution root")
    }

    @Test
    fun `resolveArtifactPath handles empty path components`() {
        // Arrange
        val runId = UUID.randomUUID()
        val workspace = workspaceManager.createWorkspace(runId)
        val pathWithEmptyComponents = "dir1//dir2/./file.txt"

        // Act
        val resolvedPath = workspaceManager.resolveArtifactPath(workspace, pathWithEmptyComponents)

        // Assert
        val expectedPath = baseWorkspaceRoot.resolve(runId.toString()).resolve("dir1/dir2/file.txt")
        assertEquals(expectedPath.normalize(), resolvedPath.normalize())
    }

    @Test
    fun `resolveArtifactPath rejects sophisticated traversal attempts`() {
        // Arrange
        val runId = UUID.randomUUID()
        val workspace = workspaceManager.createWorkspace(runId)

        val traversalAttempts = listOf(
            "dir1/../../etc/passwd",
            "./../../etc/passwd",
            "dir1/../../../etc/passwd",
            "....//....//etc/passwd",
            "dir1/dir2/../../../etc/passwd"
        )

        // Act & Assert
        traversalAttempts.forEach { attempt ->
            assertThrows<SecurityException>("Should reject traversal: $attempt") {
                workspaceManager.resolveArtifactPath(workspace, attempt)
            }
        }
    }
}
