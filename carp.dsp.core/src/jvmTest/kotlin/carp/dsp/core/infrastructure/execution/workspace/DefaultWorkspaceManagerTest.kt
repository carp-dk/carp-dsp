@file:OptIn(ExperimentalPathApi::class)

package carp.dsp.core.infrastructure.execution.workspace

import dk.cachet.carp.analytics.application.execution.workspace.WorkspacePathFormatter.formatWorkflowName
import dk.cachet.carp.analytics.application.plan.CommandSpec
import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import dk.cachet.carp.analytics.application.plan.ExpandedArg
import dk.cachet.carp.analytics.application.plan.PlannedStep
import dk.cachet.carp.analytics.application.plan.ResolvedBindings
import dk.cachet.carp.analytics.domain.workflow.StepMetadata
import dk.cachet.carp.common.application.UUID
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for [DefaultWorkspaceManager] with human-readable paths.
 *
 * Verifies that:
 * - Workflow names are formatted correctly for filesystem use
 * - Step information is extracted from ExecutionPlan
 * - Directory structure uses human-readable step names
 * - Full workspace creation and clean-up lifecycle works
 */
@Suppress("LargeClass")
class DefaultWorkspaceManagerTest
{

    private lateinit var tempDir: Path
    private lateinit var baseWorkspaceRoot: Path
    private lateinit var workspaceManager: DefaultWorkspaceManager

    @BeforeTest
    fun setup()
    {
        tempDir = Files.createTempDirectory( "workspace-hr-test" )
        baseWorkspaceRoot = tempDir.resolve( "workspaces" )
        workspaceManager = DefaultWorkspaceManager( baseWorkspaceRoot, includeTimestampInRunDir = false )
    }

    @AfterTest
    fun cleanup()
    {
        if ( tempDir.exists() )
        {
            tempDir.deleteRecursively()
        }
    }

    // ─── WorkspacePathFormatter Tests ────────────────────────────────────────

    @Test
    fun `formatWorkflowName converts to lowercase`()
    {
        // Act
        val formatted = formatWorkflowName( "Signal Processing Pipeline" )

        // Assert
        assertEquals( "signal_processing_pipeline", formatted )
    }

    @Test
    fun `formatWorkflowName replaces spaces with underscores`()
    {
        // Act
        val formatted = formatWorkflowName( "Multi Word Workflow" )

        // Assert
        assertEquals( "multi_word_workflow", formatted )
    }

    @Test
    fun `formatWorkflowName replaces dashes with underscores`()
    {
        // Act
        val formatted = formatWorkflowName( "Signal-Processing-Pipeline" )

        // Assert
        assertEquals( "signal_processing_pipeline", formatted )
    }

    @Test
    fun `formatWorkflowName removes special characters`()
    {
        // Act
        val formatted = formatWorkflowName( "Signal@Processing#Pipeline!" )

        // Assert
        assertEquals( "signalprocessingpipeline", formatted )
    }

    @Test
    fun `formatWorkflowName handles mixed case and special chars`()
    {
        // Act
        val formatted = formatWorkflowName( "EEG-Data Processing (v2.0)" )

        // Assert
        assertEquals( "eeg_data_processing_v20", formatted )
    }

    // ─── Workspace Creation Tests ────────────────────────────────────────────

    @Test
    fun `create builds workspace with human-readable path`()
    {
        // Arrange
        val runId = UUID.randomUUID()
        val plan = createTestExecutionPlan( "Signal Processing Pipeline", listOf("2") )

        // Act
        val workspace = workspaceManager.create( plan, runId )

        // Assert
        assertTrue( workspace.executionRoot.contains( "signal_processing_pipeline" ) )
        assertTrue( workspace.executionRoot.contains( "run_$runId" ) )
        assertEquals( "Signal Processing Pipeline", workspace.workflowName )
    }

    @Test
    fun `create extracts step information from plan`()
    {
        // Arrange
        val runId = UUID.randomUUID()
        val step1Name = "Import Data"
        val step2Name = "Process Data"
        val plan = createTestExecutionPlan(
            "Test Workflow",
            stepNames = listOf( step1Name, step2Name )
        )

        // Act
        val workspace = workspaceManager.create( plan, runId )

        // Assert
        assertEquals( 2, workspace.stepInfos.size )

        val stepInfos = workspace.stepInfos.values.sortedBy { it.executionIndex }
        assertEquals( step1Name, stepInfos[0].name )
        assertEquals( step2Name, stepInfos[1].name )
        assertEquals( 0, stepInfos[0].executionIndex )
        assertEquals( 1, stepInfos[1].executionIndex )
    }

    @Test
    fun `create creates execution root directory`()
    {
        // Arrange
        val runId = UUID.randomUUID()
        val plan = createTestExecutionPlan( "Test Workflow", listOf("1") )

        // Act
        val workspace = workspaceManager.create( plan, runId )

        // Assert
        val executionRootPath = Path.of( workspace.executionRoot )
        assertTrue( executionRootPath.exists() )
        assertTrue( executionRootPath.isDirectory() )
    }

    @Test
    fun `create creates steps directory`()
    {
        // Arrange
        val runId = UUID.randomUUID()
        val plan = createTestExecutionPlan( "Test Workflow", listOf("1") )

        // Act
        val workspace = workspaceManager.create( plan, runId )

        // Assert
        val stepsPath = Path.of( workspace.executionRoot ).resolve( "steps" )
        assertTrue( stepsPath.exists() )
        assertTrue( stepsPath.isDirectory() )
    }

    // ─── Step Directory Preparation Tests ────────────────────────────────────

    @Test
    fun `prepareStepDirectories creates human-readable directory structure`()
    {
        // Arrange
        val runId = UUID.randomUUID()
        val plan = createTestExecutionPlan(
            "Test Workflow",
            stepNames = listOf( "Import Data", "Process Data" )
        )
        val workspace = workspaceManager.create( plan, runId )
        val stepId = workspace.getStepIdsInOrder()[0]

        // Act
        workspaceManager.prepareStepDirectories( workspace, stepId )

        // Assert
        val stepDir = Path.of( workspace.executionRoot ).resolve( workspace.stepDir( stepId ) )
        val inputsDir = stepDir.resolve( "inputs" )
        val outputsDir = stepDir.resolve( "outputs" )
        val logsDir = stepDir.resolve( "logs" )

        assertTrue( stepDir.exists() && stepDir.isDirectory() )
        assertTrue( stepDir.toString().contains( "01_import_data" ) )
        assertTrue( inputsDir.exists() )
        assertTrue( outputsDir.exists() )
        assertTrue( logsDir.exists() )
    }

    @Test
    fun `prepareStepDirectories creates separate directories for multiple steps`()
    {
        // Arrange
        val runId = UUID.randomUUID()
        val plan = createTestExecutionPlan(
            "Multi-Step Workflow",
            stepNames = listOf( "Step One", "Step Two", "Step Three" )
        )
        val workspace = workspaceManager.create( plan, runId )
        val stepIds = workspace.getStepIdsInOrder()

        // Act
        stepIds.forEach { stepId ->
            workspaceManager.prepareStepDirectories( workspace, stepId )
        }

        // Assert
        val stepsPath = Path.of( workspace.executionRoot ).resolve( "steps" )
        val stepDirs = stepsPath.toFile().listFiles() ?: emptyArray()

        assertEquals( 3, stepDirs.size )
        assertTrue( stepDirs.any { it.name == "01_step_one" } )
        assertTrue( stepDirs.any { it.name == "02_step_two" } )
        assertTrue( stepDirs.any { it.name == "03_step_three" } )
    }

    // ─── Cleanup Tests ──────────────────────────────────────────────────────

    @Test
    fun `cleanup deletes entire workspace directory`()
    {
        // Arrange
        val runId = UUID.randomUUID()
        val plan = createTestExecutionPlan( "Test Workflow", listOf("1") )
        val workspace = workspaceManager.create( plan, runId )

        val executionRootPath = Path.of( workspace.executionRoot )
        assertTrue( executionRootPath.exists() )

        // Act
        val success = workspaceManager.cleanup( workspace )

        // Assert
        assertTrue( success )
        assertFalse( executionRootPath.exists() )
    }

    @Test
    fun `cleanup is idempotent`()
    {
        // Arrange
        val runId = UUID.randomUUID()
        val plan = createTestExecutionPlan( "Test Workflow", listOf("1") )
        val workspace = workspaceManager.create( plan, runId )

        // Act
        val firstCleanup = workspaceManager.cleanup( workspace )
        val secondCleanup = workspaceManager.cleanup( workspace )

        // Assert
        assertTrue( firstCleanup )
        assertTrue( secondCleanup )
    }

    // ─── Path Resolution Tests ──────────────────────────────────────────────

    @Test
    fun `resolveStepWorkingDir returns correct path`()
    {
        // Arrange
        val runId = UUID.randomUUID()
        val plan = createTestExecutionPlan(
            "Test Workflow",
            stepNames = listOf( "Import Data", "Process Data" )
        )
        val workspace = workspaceManager.create( plan, runId )
        val stepId = workspace.getStepIdsInOrder()[1]

        // Act
        val workingDir = workspaceManager.resolveStepWorkingDir( workspace, stepId )

        // Assert
        assertTrue( workingDir.contains( "02_process_data" ) )
        assertTrue( Path.of( workingDir ).isAbsolute )
    }

    @Test
    fun `resolveStepOutputArtifact enforces outputs directory containment`()
    {
        // Arrange
        val runId = UUID.randomUUID()
        val plan = createTestExecutionPlan( "Test Workflow", listOf("1") )
        val workspace = workspaceManager.create( plan, runId )
        val stepId = workspace.getStepIdsInOrder()[0]
        workspaceManager.prepareStepDirectories( workspace, stepId )

        // Act
        val artifactPath = workspaceManager.resolveStepOutputArtifact(
            workspace,
            stepId,
            workspace.stepOutputsDir( stepId ) + "/result.txt"
        )

        // Assert
        assertTrue( artifactPath.exists() || artifactPath.parent.exists() )
        assertTrue( artifactPath.toString().contains( "01_" ) )
        assertTrue( artifactPath.toString().contains( "outputs" ) )
    }

    // ─── Timestamp Handling Tests ───────────────────────────────────────────

    @Test
    fun `create includes timestamp in run directory when enabled`()
    {
        // Arrange
        val timestampedManager = DefaultWorkspaceManager(
            baseWorkspaceRoot,
            includeTimestampInRunDir = true
        )
        val runId = UUID.randomUUID()
        val plan = createTestExecutionPlan( "Test Workflow", listOf("1") )

        // Act
        val workspace = timestampedManager.create( plan, runId )

        // Assert - should contain timestamp pattern YYYYMMDD_HHMMSS
        assertTrue( workspace.executionRoot.contains( "run_$runId" ) )
        assertTrue( workspace.executionRoot.matches( Regex(".*run_[a-f0-9-]+_\\d{8}_\\d{6}.*") ) )
    }

    @Test
    fun `create without timestamp in run directory`()
    {
        // Arrange
        val nonTimestampedManager = DefaultWorkspaceManager(
            baseWorkspaceRoot,
            includeTimestampInRunDir = false
        )
        val runId = UUID.randomUUID()
        val plan = createTestExecutionPlan( "Test Workflow", listOf("1") )

        // Act
        val workspace = nonTimestampedManager.create( plan, runId )

        // Assert - should NOT contain timestamp
        assertTrue( workspace.executionRoot.contains( "run_$runId" ) )
        assertFalse( workspace.executionRoot.matches( Regex(".*run_[a-f0-9-]+_\\d{8}_\\d{6}.*") ) )
    }

    // ─── Initialization Tests ───────────────────────────────────────────────

    @Test
    fun `constructor throws on non-absolute base workspace root`()
    {
        // Arrange
        val relativePath = Path.of( "relative/path" )

        // Act & Assert
        try
        {
            DefaultWorkspaceManager( relativePath )
            throw AssertionError( "Should have thrown IllegalArgumentException" )
        } catch ( e: IllegalArgumentException )
        {
            assertTrue( e.message?.contains( "absolute path" ) ?: false )
        }
    }

    // ─── Path Resolution and Validation Tests ───────────────────────────────

    @Test
    fun `resolveStepOutputArtifact allows valid paths within outputs directory`()
    {
        // Arrange
        val runId = UUID.randomUUID()
        val plan = createTestExecutionPlan( "Test Workflow", listOf("1") )
        val workspace = workspaceManager.create( plan, runId )
        val stepId = workspace.getStepIdsInOrder()[0]
        workspaceManager.prepareStepDirectories( workspace, stepId )

        // Act
        val artifactPath = workspaceManager.resolveStepOutputArtifact(
            workspace,
            stepId,
            workspace.stepOutputsDir( stepId ) + "/data.csv"
        )

        // Assert
        assertTrue( artifactPath.toString().contains( "outputs" ) )
        assertTrue( artifactPath.toString().contains( "01_" ) )
    }

    @Test
    fun `resolveStepOutputArtifact rejects path traversal attacks`()
    {
        // Arrange
        val runId = UUID.randomUUID()
        val plan = createTestExecutionPlan( "Test Workflow", listOf("1") )
        val workspace = workspaceManager.create( plan, runId )
        val stepId = workspace.getStepIdsInOrder()[0]

        // Act & Assert - attempt to escape execution root
        try
        {
            workspaceManager.resolveStepOutputArtifact(
                workspace,
                stepId,
                "../../../../etc/passwd"
            )
            throw AssertionError( "Should have thrown SecurityException" )
        } catch ( e: SecurityException )
        {
            assertTrue( e.message?.contains( "Path traversal" ) ?: false )
        }
    }

    @Test
    fun `resolveStepOutputArtifact rejects paths outside outputs directory`()
    {
        // Arrange
        val runId = UUID.randomUUID()
        val plan = createTestExecutionPlan( "Test Workflow", listOf("1") )
        val workspace = workspaceManager.create( plan, runId )
        val stepId = workspace.getStepIdsInOrder()[0]
        workspaceManager.prepareStepDirectories( workspace, stepId )

        // Act & Assert - try to write to inputs directory
        try
        {
            workspaceManager.resolveStepOutputArtifact(
                workspace,
                stepId,
                workspace.stepInputsDir( stepId ) + "/data.csv"
            )
            throw AssertionError( "Should have thrown IllegalArgumentException" )
        } catch ( e: IllegalArgumentException )
        {
            assertTrue( e.message?.contains( "outputs directory" ) ?: false )
        }
    }

    @Test
    fun `resolveStepWorkingDir returns absolute path`()
    {
        // Arrange
        val runId = UUID.randomUUID()
        val plan = createTestExecutionPlan( "Test Workflow", listOf("1") )
        val workspace = workspaceManager.create( plan, runId )
        val stepId = workspace.getStepIdsInOrder()[0]

        // Act
        val workingDir = workspaceManager.resolveStepWorkingDir( workspace, stepId )

        // Assert
        assertTrue( Path.of( workingDir ).isAbsolute )
    }

    // ─── Invalid Step ID Tests ──────────────────────────────────────────────

    @Test
    fun `prepareStepDirectories throws on unknown step ID`()
    {
        // Arrange
        val runId = UUID.randomUUID()
        val plan = createTestExecutionPlan( "Test Workflow", listOf("1") )
        val workspace = workspaceManager.create( plan, runId )
        val unknownStepId = UUID.randomUUID()

        // Act & Assert
        try
        {
            workspaceManager.prepareStepDirectories( workspace, unknownStepId )
            throw AssertionError( "Should have thrown IllegalArgumentException" )
        } catch ( _: IllegalArgumentException )
        {
            // Expected
        }
    }

    @Test
    fun `resolveStepWorkingDir throws on unknown step ID`()
    {
        // Arrange
        val runId = UUID.randomUUID()
        val plan = createTestExecutionPlan( "Test Workflow", listOf("1") )
        val workspace = workspaceManager.create( plan, runId )
        val unknownStepId = UUID.randomUUID()

        // Act & Assert
        try
        {
            workspaceManager.resolveStepWorkingDir( workspace, unknownStepId )
            throw AssertionError( "Should have thrown IllegalArgumentException" )
        } catch ( _: IllegalArgumentException )
        {
            // Expected
        }
    }

    // ─── Workflow Name Formatting Edge Cases ────────────────────────────────

    @Test
    fun `formatWorkflowName handles empty string`()
    {
        // Act
        val formatted = formatWorkflowName( "" )

        // Assert
        assertEquals( "", formatted )
    }

    @Test
    fun `formatWorkflowName handles only special characters`()
    {
        // Act
        val formatted = formatWorkflowName( "@#$%^&*()" )

        // Assert
        assertEquals( "", formatted )
    }

    @Test
    fun `formatWorkflowName handles numbers and underscores`()
    {
        // Act
        val formatted = formatWorkflowName( "Workflow_V2.0_Beta" )

        // Assert
        assertEquals( "workflow_v20_beta", formatted )
    }

    @Test
    fun `formatWorkflowName handles consecutive spaces and dashes`()
    {
        // Act
        val formatted = formatWorkflowName( "Signal  --  Processing" )

        // Assert
        assertEquals( "signal______processing", formatted )
    }

    // ─── Step Information Extraction Tests ──────────────────────────────────

    @Test
    fun `create preserves step order from plan`()
    {
        // Arrange
        val runId = UUID.randomUUID()
        val stepNames = listOf( "First", "Second", "Third" )
        val plan = createTestExecutionPlan( "Test Workflow", stepNames )

        // Act
        val workspace = workspaceManager.create( plan, runId )

        // Assert
        val orderedStepInfos = workspace.stepInfos.values.sortedBy { it.executionIndex }
        assertEquals( stepNames, orderedStepInfos.map { it.name } )
    }

    @Test
    fun `create assigns correct execution indices to steps`()
    {
        // Arrange
        val runId = UUID.randomUUID()
        val stepNames = listOf( "Alpha", "Beta", "Gamma", "Delta" )
        val plan = createTestExecutionPlan( "Test Workflow", stepNames )

        // Act
        val workspace = workspaceManager.create( plan, runId )

        // Assert
        val orderedStepInfos = workspace.stepInfos.values.sortedBy { it.executionIndex }
        for ( i in orderedStepInfos.indices )
        {
            assertEquals( i, orderedStepInfos[i].executionIndex )
        }
    }

    @Test
    fun `create with single step`()
    {
        // Arrange
        val runId = UUID.randomUUID()
        val plan = createTestExecutionPlan( "Test Workflow", listOf("Only Step") )

        // Act
        val workspace = workspaceManager.create( plan, runId )

        // Assert
        assertEquals( 1, workspace.stepInfos.size )
        val stepInfo = workspace.stepInfos.values.first()
        assertEquals( "Only Step", stepInfo.name )
        assertEquals( 0, stepInfo.executionIndex )
    }

    @Test
    fun `create with empty steps list`()
    {
        // Arrange
        val runId = UUID.randomUUID()
        val plan = createTestExecutionPlan( "Test Workflow", emptyList() )

        // Act
        val workspace = workspaceManager.create( plan, runId )

        // Assert
        assertTrue( workspace.stepInfos.isEmpty() )
        assertTrue( workspace.getStepIdsInOrder().isEmpty() )
    }

    // ─── Complex Workflow Tests ─────────────────────────────────────────────

    @Test
    fun `complete signal processing pipeline workflow`()
    {
        // Arrange: Create a realistic signal processing workflow
        val runId = UUID.randomUUID()
        val workflow = "EEG Signal Processing Pipeline"
        val steps = listOf(
            "Validate Input",
            "Preprocess EEG",
            "Extract Features",
            "Generate Report"
        )
        val plan = createTestExecutionPlan( workflow, stepNames = steps )

        // Act: Create workspace
        val workspace = workspaceManager.create( plan, runId )

        // Assert workspace structure
        assertTrue( workspace.executionRoot.contains( "eeg_signal_processing_pipeline" ) )
        assertEquals( 4, workspace.stepInfos.size )

        // Act: Prepare all step directories
        val stepIds = workspace.getStepIdsInOrder()
        stepIds.forEach { stepId ->
            workspaceManager.prepareStepDirectories( workspace, stepId )
        }

        // Assert directory structure
        val stepsPath = Path.of( workspace.executionRoot ).resolve( "steps" )
        val stepDirs = stepsPath.toFile().listFiles() ?: emptyArray()
        assertEquals( 4, stepDirs.size )

        assertTrue( stepDirs.any { it.name == "01_validate_input" } )
        assertTrue( stepDirs.any { it.name == "02_preprocess_eeg" } )
        assertTrue( stepDirs.any { it.name == "03_extract_features" } )
        assertTrue( stepDirs.any { it.name == "04_generate_report" } )

        // Verify each step has proper subdirectories
        stepIds.forEach { stepId ->
            val stepPath = Path.of( workspace.executionRoot ).resolve( workspace.stepDir( stepId ) )
            assertTrue( stepPath.resolve( "inputs" ).exists() )
            assertTrue( stepPath.resolve( "outputs" ).exists() )
            assertTrue( stepPath.resolve( "logs" ).exists() )
        }

        // Act: Cleanup
        val success = workspaceManager.cleanup( workspace )

        // Assert clean-up
        assertTrue( success )
        assertFalse( Path.of( workspace.executionRoot ).exists() )
    }

    // ─── Additional Path Validation Tests ────────────────────────────────────

    @Test
    fun `resolveStepOutputArtifact with nested relative paths`()
    {
        // Arrange
        val runId = UUID.randomUUID()
        val plan = createTestExecutionPlan( "Test Workflow", listOf("1") )
        val workspace = workspaceManager.create( plan, runId )
        val stepId = workspace.getStepIdsInOrder()[0]
        workspaceManager.prepareStepDirectories( workspace, stepId )

        // Act
        val artifactPath = workspaceManager.resolveStepOutputArtifact(
            workspace,
            stepId,
            workspace.stepOutputsDir( stepId ) + "/subdir/nested/result.csv"
        )

        // Assert - check for path components (cross-platform compatible)
        val normalizedPath = artifactPath.toString().replace( "\\", "/" )
        assertTrue( normalizedPath.contains( "outputs" ) )
        assertTrue( normalizedPath.contains( "subdir" ) )
        assertTrue( normalizedPath.contains( "nested" ) )
        assertTrue( normalizedPath.endsWith( "result.csv" ) )
    }

    @Test
    fun `resolveStepOutputArtifact rejects double dot path components`()
    {
        // Arrange
        val runId = UUID.randomUUID()
        val plan = createTestExecutionPlan( "Test Workflow", listOf("1") )
        val workspace = workspaceManager.create( plan, runId )
        val stepId = workspace.getStepIdsInOrder()[0]

        // Act & Assert
        try
        {
            workspaceManager.resolveStepOutputArtifact(
                workspace,
                stepId,
                workspace.stepOutputsDir( stepId ) + "/../../../../../etc/passwd"
            )
            throw AssertionError( "Should have thrown SecurityException" )
        } catch ( e: SecurityException )
        {
            assertTrue( e.message?.contains( "Path traversal" ) ?: false )
        }
    }

    @Test
    fun `resolveStepOutputArtifact rejects absolute path attempts`()
    {
        // Arrange
        val runId = UUID.randomUUID()
        val plan = createTestExecutionPlan( "Test Workflow", listOf("1") )
        val workspace = workspaceManager.create( plan, runId )
        val stepId = workspace.getStepIdsInOrder()[0]

        // Act & Assert - absolute paths should be rejected
        try
        {
            workspaceManager.resolveStepOutputArtifact(
                workspace,
                stepId,
                "/etc/passwd"
            )
            throw AssertionError( "Should have thrown SecurityException" )
        } catch ( e: SecurityException )
        {
            assertTrue( e.message?.contains( "Path traversal" ) ?: false )
        }
    }

    // ─── Cleanup Idempotency Tests ──────────────────────────────────────────

    @Test
    fun `cleanup returns true for already deleted workspace`()
    {
        // Arrange
        val runId = UUID.randomUUID()
        val plan = createTestExecutionPlan( "Test Workflow", listOf("1") )
        val workspace = workspaceManager.create( plan, runId )

        // Act
        workspaceManager.cleanup( workspace )
        val secondCleanup = workspaceManager.cleanup( workspace )

        // Assert
        assertTrue( secondCleanup )
    }

    @Test
    fun `cleanup returns true for non-existent workspace`()
    {
        // Arrange
        val runId = UUID.randomUUID()
        val fakeWorkspace = dk.cachet.carp.analytics.application.execution.workspace.ExecutionWorkspace(
            runId = runId,
            executionRoot = "/non/existent/path",
            workflowName = "Fake Workflow",
            stepInfos = emptyMap()
        )

        // Act
        val result = workspaceManager.cleanup( fakeWorkspace )

        // Assert
        assertTrue( result )
    }

    // ─── Step Directory Creation Tests ──────────────────────────────────────

    @Test
    fun `prepareStepDirectories creates all required subdirectories`()
    {
        // Arrange
        val runId = UUID.randomUUID()
        val plan = createTestExecutionPlan( "Test Workflow", listOf("Test Step") )
        val workspace = workspaceManager.create( plan, runId )
        val stepId = workspace.getStepIdsInOrder()[0]

        // Act
        workspaceManager.prepareStepDirectories( workspace, stepId )

        // Assert - verify all three subdirectories exist
        val stepPath = Path.of( workspace.executionRoot ).resolve( workspace.stepDir( stepId ) )
        assertTrue( stepPath.resolve( "inputs" ).exists() )
        assertTrue( stepPath.resolve( "outputs" ).exists() )
        assertTrue( stepPath.resolve( "logs" ).exists() )
        assertTrue( stepPath.isDirectory() )
    }

    @Test
    fun `prepareStepDirectories is idempotent`()
    {
        // Arrange
        val runId = UUID.randomUUID()
        val plan = createTestExecutionPlan( "Test Workflow", listOf("Test Step") )
        val workspace = workspaceManager.create( plan, runId )
        val stepId = workspace.getStepIdsInOrder()[0]

        // Act
        workspaceManager.prepareStepDirectories( workspace, stepId )
        val firstModTime = Path.of( workspace.executionRoot )
            .resolve( workspace.stepDir( stepId ) )
            .toFile()
            .lastModified()

        Thread.sleep( 100 )

        workspaceManager.prepareStepDirectories( workspace, stepId )
        val secondModTime = Path.of( workspace.executionRoot )
            .resolve( workspace.stepDir( stepId ) )
            .toFile()
            .lastModified()

        // Assert - second call should not recreate directories
        assertEquals( firstModTime, secondModTime )
    }

    // ─── Artifact Path Resolution Edge Cases ────────────────────────────────

    @Test
    fun `resolveStepOutputArtifact with current directory reference`()
    {
        // Arrange
        val runId = UUID.randomUUID()
        val plan = createTestExecutionPlan( "Test Workflow", listOf("1") )
        val workspace = workspaceManager.create( plan, runId )
        val stepId = workspace.getStepIdsInOrder()[0]
        workspaceManager.prepareStepDirectories( workspace, stepId )

        // Act
        val artifactPath = workspaceManager.resolveStepOutputArtifact(
            workspace,
            stepId,
            workspace.stepOutputsDir( stepId ) + "/./result.csv"
        )

        // Assert
        assertTrue( artifactPath.toString().contains( "outputs" ) )
    }

    @Test
    fun `resolveStepOutputArtifact ensures output containment with path normalization`()
    {
        // Arrange
        val runId = UUID.randomUUID()
        val plan = createTestExecutionPlan( "Test Workflow", listOf("1") )
        val workspace = workspaceManager.create( plan, runId )
        val stepId = workspace.getStepIdsInOrder()[0]
        workspaceManager.prepareStepDirectories( workspace, stepId )

        // Act
        val artifactPath = workspaceManager.resolveStepOutputArtifact(
            workspace,
            stepId,
            workspace.stepOutputsDir( stepId ) + "/../outputs/result.csv"
        )

        // Assert - normalize path separators for cross-platform compatibility
        val normalizedPath = artifactPath.toString().replace( "\\", "/" )
        assertTrue( normalizedPath.contains( "outputs" ) )
        assertTrue( normalizedPath.endsWith( "result.csv" ) )
    }

    // ─── Multiple Workspace Instances ───────────────────────────────────────

    @Test
    fun `multiple concurrent workspace creation does not interfere`()
    {
        // Arrange
        val runId1 = UUID.randomUUID()
        val runId2 = UUID.randomUUID()
        val plan1 = createTestExecutionPlan( "Workflow1", listOf("Step1") )
        val plan2 = createTestExecutionPlan( "Workflow2", listOf("Step2") )

        // Act
        val workspace1 = workspaceManager.create( plan1, runId1 )
        val workspace2 = workspaceManager.create( plan2, runId2 )

        // Assert - both should exist independently
        assertTrue( Path.of( workspace1.executionRoot ).exists() )
        assertTrue( Path.of( workspace2.executionRoot ).exists() )
        assertNotEquals(workspace1.executionRoot, workspace2.executionRoot)

        // Cleanup
        workspaceManager.cleanup( workspace1 )
        workspaceManager.cleanup( workspace2 )
    }

    @Test
    fun `workflow names with unicode characters are handled safely`()
    {
        // Act
        val formatted = formatWorkflowName( "工作流 Workflow №123" )

        // Assert - should handle Unicode and special chars safely
        // The exact output may vary based on implementation, but it should:
        // - Be safe for filesystem use (no special chars)
        // - Preserve alphanumeric content where possible
        assertFalse( formatted.contains( "工" ) ) // Unicode should be removed
        assertFalse( formatted.contains( "№" ) ) // Special chars should be removed
        assertTrue( formatted.contains( "123" ) || formatted.isEmpty() ) // Numbers may be preserved
    }

    // ─── Helper Methods ─────────────────────────────────────────────────────

    private fun createTestExecutionPlan(
        workflowId: String = "test-workflow",
        stepNames: List<String> = listOf( "Step 1" )
    ): ExecutionPlan
    {
        val steps = stepNames.mapIndexed { _, name ->
            createTestPlannedStep(
                UUID.randomUUID(),
                name
            )
        }

        return ExecutionPlan(
            workflowName = workflowId,
            planId = "test-plan",
            steps = steps
        )
    }

    private fun createTestPlannedStep(
        stepId: UUID,
        name: String
    ): PlannedStep
    {
        return PlannedStep(
            metadata = StepMetadata(
                id = stepId,
                name = name
            ),
            process = CommandSpec( "echo", listOf( ExpandedArg.Literal( "test" ) ) ),
            bindings = ResolvedBindings( emptyMap(), emptyMap() ),
            environmentRef = UUID.randomUUID()
        )
    }
}
