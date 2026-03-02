package carp.dsp.core.application.environment.workspace

import dk.cachet.carp.analytics.application.execution.workspace.ExecutionWorkspace
import dk.cachet.carp.analytics.application.execution.workspace.WorkspaceManager
import dk.cachet.carp.common.application.UUID
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * Filesystem-based implementation of [WorkspaceManager].
 *
 * This implementation provides concrete filesystem operations for workspace management:
 * - Creates actual directories on the filesystem
 * - Normalizes and validates paths for security
 * - Prevents path traversal attacks
 * - Ensures all paths are contained within the execution root
 *
 * @param baseWorkspaceRoot The base directory where all execution workspaces will be created.
 *                          This should be an absolute path to a directory with appropriate permissions.
 */
class DefaultWorkspaceManager(
    private val baseWorkspaceRoot: Path
) : WorkspaceManager {

    private val pathResolver = FileSystemPathResolver(baseWorkspaceRoot)

    init {
        require(baseWorkspaceRoot.isAbsolute) {
            "Base workspace root must be an absolute path: $baseWorkspaceRoot"
        }

        // Ensure the base workspace directory exists
        try {
            baseWorkspaceRoot.createDirectories()
        } catch (e: IOException) {
            throw IllegalStateException("Failed to create base workspace directory: $baseWorkspaceRoot", e)
        }
    }

    /**
     * Creates a new execution workspace for the given run ID.
     *
     * The workspace directory structure created is:
     * ```
     * baseWorkspaceRoot/
     *   {runId}/              # executionRoot
     *     steps/              # Created immediately for consistency
     * ```
     *
     * @param runId Unique identifier for the execution run
     * @return A new ExecutionWorkspace instance with the run's logical root
     * @throws IllegalStateException if directory creation fails
     */
    override fun createWorkspace(runId: UUID): ExecutionWorkspace {
        val executionRootPath = baseWorkspaceRoot.resolve(runId.toString())
        val stepsPath = executionRootPath.resolve("steps")

        try {
            // Create the execution root directory
            executionRootPath.createDirectories()

            // Create the steps directory immediately for consistency
            stepsPath.createDirectories()

            return ExecutionWorkspace(
                runId = runId,
                executionRoot = runId.toString()
            )
        } catch (e: IOException) {
            throw IllegalStateException("Failed to create workspace for run $runId at $executionRootPath", e)
        }
    }

    /**
     * Prepares the directory structure for a specific step within the workspace.
     *
     * Creates the complete step directory structure:
     * ```
     * {executionRoot}/
     *   steps/
     *     {stepId}/
     *       inputs/
     *       outputs/
     *       logs/
     * ```
     *
     * @param workspace The execution workspace
     * @param stepId The step identifier UUID
     * @throws IllegalStateException if directory creation fails
     */
    override fun prepareStepDirectories(workspace: ExecutionWorkspace, stepId: UUID) {
        val executionRoot = pathResolver.resolveExecutionRoot(workspace.executionRoot)

        // Create all step directories
        val stepPath = executionRoot.resolve(workspace.stepDir(stepId))
        val inputsPath = executionRoot.resolve(workspace.stepInputsDir(stepId))
        val outputsPath = executionRoot.resolve(workspace.stepOutputsDir(stepId))
        val logsPath = executionRoot.resolve(workspace.stepLogsDir(stepId))

        try {
            stepPath.createDirectories()
            inputsPath.createDirectories()
            outputsPath.createDirectories()
            logsPath.createDirectories()
        } catch (e: IOException) {
            throw IllegalStateException(
                "Failed to create step directories for step $stepId in workspace ${workspace.runId}",
                e
            )
        }
    }

    /**
     * Resolves a relative artifact path to an absolute filesystem path within the workspace.
     *
     * This method ensures that:
     * 1. The path is normalized and safe from traversal attacks
     * 2. The resolved path is contained within the execution root
     * 3. The path is converted to an absolute filesystem path
     *
     * @param workspace The execution workspace
     * @param relativePath The relative path to resolve
     * @return The absolute Path within the workspace
     * @throws SecurityException if the path attempts to escape the execution root
     */
    fun resolveArtifactPath(workspace: ExecutionWorkspace, relativePath: String): Path {
        val executionRoot = pathResolver.resolveExecutionRoot(workspace.executionRoot)
        return pathResolver.validateAndResolve(executionRoot, relativePath)
    }

    /**
     * Gets the absolute filesystem path for a step's working directory.
     *
     * @param workspace The execution workspace
     * @param stepId The step identifier UUID
     * @return The absolute Path to the step's directory
     */
    fun getStepWorkingDirectory(workspace: ExecutionWorkspace, stepId: UUID): Path {
        val executionRoot = pathResolver.resolveExecutionRoot(workspace.executionRoot)
        return executionRoot.resolve(workspace.stepDir(stepId))
    }
}
