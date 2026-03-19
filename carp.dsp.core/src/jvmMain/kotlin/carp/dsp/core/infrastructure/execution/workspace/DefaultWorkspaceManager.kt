package carp.dsp.core.infrastructure.execution.workspace

import dk.cachet.carp.analytics.application.execution.workspace.ExecutionWorkspace
import dk.cachet.carp.analytics.application.execution.workspace.StepInfo
import dk.cachet.carp.analytics.application.execution.workspace.WorkspaceManager
import dk.cachet.carp.analytics.application.execution.workspace.WorkspacePathFormatter
import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import dk.cachet.carp.common.application.UUID
import java.io.IOException
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists



/**
 * Filesystem-based implementation of [WorkspaceManager] with human-readable paths.
 *
 * Creates workspaces with structure:
 * ```
 * baseWorkspaceRoot/
 *   {workflowName}/
 *     run_{runId}/
 *       steps/
 *         01_import_data/
 *           inputs/
 *           outputs/
 *           logs/
 *         02_process_eeg/
 *           inputs/
 *           outputs/
 *           logs/
 * ```
 *
 * This implementation provides:
 * - Filesystem operations for workspace management
 * - Path normalization and security validation
 * - Path traversal attack prevention
 * - Idempotent clean-up (safe to call multiple times)
 * - Human-readable directory names with execution indices
 *
 * @param baseWorkspaceRoot The base directory where all execution workspaces will be created.
 *                          Must be an absolute path with appropriate permissions.
 * @param includeTimestampInRunDir Whether to include timestamp in run directory (e.g., run_{runId}_{timestamp})
 * @throws IllegalArgumentException if baseWorkspaceRoot is not absolute
 * @throws IllegalStateException if base directory creation fails
 */
class DefaultWorkspaceManager(
    private val baseWorkspaceRoot: Path,
    private val includeTimestampInRunDir: Boolean = false
) : WorkspaceManager
{

    init
    {
        require( baseWorkspaceRoot.isAbsolute) {
            "Base workspace root must be an absolute path: $baseWorkspaceRoot"
        }

        try
        {
            baseWorkspaceRoot.createDirectories()
        } catch ( e: IOException ) {
            throw IllegalStateException(
                "Failed to create base workspace directory: $baseWorkspaceRoot",
                e
            )
        }
    }

    /**
     * Creates a new execution workspace for the given execution plan and run ID.
     *
     * Extracts step names and metadata from the plan to create human-readable
     * directory names.
     *
     * The workspace directory structure created:
     * ```
     * baseWorkspaceRoot/
     *   {workflowName}/
     *     run_{runId}/
     *       steps/
     * ```
     *
     * @param plan The execution plan (used to extract workflow name and step info)
     * @param runId Unique identifier for the execution run
     * @return A new ExecutionWorkspace instance with human-readable paths
     * @throws IllegalStateException if directory creation fails
     */
    override fun create( plan: ExecutionPlan, runId: UUID ): ExecutionWorkspace
    {
        val workflowName = plan.workflowName
        val formattedWorkflowName = WorkspacePathFormatter.formatWorkflowName( workflowName )

        // Build run directory name
        val runDirName = if ( includeTimestampInRunDir )
        {
            val timestamp = formatTimestamp( Instant.now() )
            "run_${runId}_$timestamp"
        } else {
            "run_$runId"
        }

        // Extract step information from plan
        val stepInfos = plan.steps
            .mapIndexed { index, step ->
                step.metadata.id to StepInfo(
                    id = step.metadata.id,
                    name = step.metadata.name,
                    executionIndex = index
                )
            }
            .toMap()

        // Build execution root path
        val executionRootPath = baseWorkspaceRoot
            .resolve( formattedWorkflowName )
            .resolve( runDirName )
        val stepsPath = executionRootPath.resolve( "steps" )

        try
        {
            executionRootPath.createDirectories()
            stepsPath.createDirectories()

            return ExecutionWorkspace(
                runId = runId,
                executionRoot = executionRootPath.toString(),
                workflowName = workflowName,
                stepInfos = stepInfos
            )
        } catch ( e: IOException ) {
            throw IllegalStateException(
                "Failed to create workspace for run $runId at $executionRootPath",
                e
            )
        }
    }

    /**
     * Prepares the directory structure for a specific step within the workspace.
     *
     * Creates the complete step directory structure with human-readable names:
     * ```
     * {executionRoot}/
     *   steps/
     *     01_import_data/
     *       inputs/
     *       outputs/
     *       logs/
     * ```
     *
     * @param workspace The execution workspace
     * @param stepId The step identifier UUID
     * @throws IllegalStateException if directory creation fails
     * @throws IllegalArgumentException if stepId is not in workspace
     */
    override fun prepareStepDirectories( workspace: ExecutionWorkspace, stepId: UUID )
    {
        val executionRootPath = Path.of( workspace.executionRoot )

        // Get step info (throws if not found)
        val stepInputsPath = executionRootPath.resolve( workspace.stepInputsDir( stepId ) )
        val stepOutputsPath = executionRootPath.resolve( workspace.stepOutputsDir( stepId ) )
        val stepLogsPath = executionRootPath.resolve( workspace.stepLogsDir( stepId ) )

        try
        {
            stepInputsPath.createDirectories()
            stepOutputsPath.createDirectories()
            stepLogsPath.createDirectories()
        } catch ( e: IOException ) {
            throw IllegalStateException(
                "Failed to create step directories for step $stepId in workspace ${workspace.runId}",
                e
            )
        }
    }

    /**
     * Cleans up the workspace after execution completes.
     *
     * Safely removes the entire workspace directory tree. If the workspace directory
     * doesn't exist, this method completes silently (idempotent behaviour).
     *
     * @param workspace The execution workspace to clean up
     * @return true if cleanup succeeded (directory deleted or didn't exist)
     */
    @OptIn(ExperimentalPathApi::class)
    override fun cleanup(workspace: ExecutionWorkspace ): Boolean
    {
        val executionRootPath = Path.of( workspace.executionRoot )

        return try
        {
            if ( executionRootPath.exists() )
            {
                executionRootPath.deleteRecursively()
            }
            true
        } catch ( e: IOException ) {
            System.err.println(
                "Warning: failed to cleanup workspace ${workspace.runId}: ${e.message}"
            )
            false
        }
    }

    /**
     * Returns the absolute filesystem path for a step's working directory.
     *
     * @param workspace The execution workspace
     * @param stepId The step identifier UUID
     * @return Absolute path string to the step's working directory
     * @throws IllegalArgumentException if stepId is not in workspace
     */
    override fun resolveStepWorkingDir( workspace: ExecutionWorkspace, stepId: UUID ): String
    {
        val executionRootPath = Path.of( workspace.executionRoot )
        return executionRootPath.resolve( workspace.stepDir( stepId ) ).toString()
    }

    /**
     * Resolves and validates that a declared artifact path is within the step's outputs directory.
     *
     * This method enforces that all declared artefacts must be placed within the step's
     * outputs directory to ensure proper isolation and prevent cross-step contamination.
     *
     * @param workspace The execution workspace
     * @param stepId The step identifier UUID
     * @param relativePath The relative artefact path declared by the step
     * @return The absolute Path to the artefact within the step's outputs directory
     * @throws SecurityException if the path attempts to escape the execution root
     * @throws IllegalArgumentException if the resolved path is not within the step's outputs directory
     */
    fun resolveStepOutputArtifact(
        workspace: ExecutionWorkspace,
        stepId: UUID,
        relativePath: String
    ): Path
    {
        val executionRootPath = Path.of( workspace.executionRoot )
        val outputsDir = executionRootPath.resolve( workspace.stepOutputsDir( stepId ) )

        // Resolve and normalize the path safely
        val resolvedPath = validateAndResolvePath( executionRootPath, relativePath )

        // Enforce that it must be within the step's outputs directory
        if ( !isPathContainedWithin( outputsDir, resolvedPath ) )
        {
            throw IllegalArgumentException(
                "Declared artifact path '$relativePath' must resolve within step outputs directory. " +
                        "Resolved: $resolvedPath, Required parent: $outputsDir"
            )
        }

        return resolvedPath
    }

    /**
     * Safely resolves a relative path within the execution root.
     *
     * Prevents path traversal attacks and ensures the resolved path is contained
     * within the execution root.
     *
     * @param executionRoot The execution root path
     * @param relativePath The relative path to resolve
     * @return The normalized absolute path
     * @throws SecurityException if the path attempts to escape the execution root
     */
    private fun validateAndResolvePath( executionRoot: Path, relativePath: String ): Path
    {
        val resolvedPath = executionRoot.resolve( relativePath ).normalize()

        // Security check: ensure path doesn't escape the execution root
        if ( !resolvedPath.startsWith( executionRoot.normalize() ) )
        {
            throw SecurityException(
                "Path traversal attack detected: '$relativePath' attempts to escape " +
                        "execution root '$executionRoot'"
            )
        }

        return resolvedPath
    }

    /**
     * Checks if a path is contained within a parent directory.
     *
     * @param parent The parent directory
     * @param path The path to check
     * @return true if path is contained within parent
     */
    private fun isPathContainedWithin( parent: Path, path: Path ): Boolean
    {
        return path.normalize().startsWith( parent.normalize() )
    }

    /**
     * Formats a timestamp for use in a filesystem path.
     *
     * Format: "YYYYMMDD_HHMMSS"
     *
     * @param instant The instant to format
     * @return Formatted timestamp
     */
    fun formatTimestamp( instant: Instant ): String
    {
        val dateTime = java.time.LocalDateTime.ofInstant(
            instant,
            java.time.ZoneId.systemDefault()
        )
        return dateTime.format(
            java.time.format.DateTimeFormatter.ofPattern( "yyyyMMdd_HHmmss" )
        )
    }
}
