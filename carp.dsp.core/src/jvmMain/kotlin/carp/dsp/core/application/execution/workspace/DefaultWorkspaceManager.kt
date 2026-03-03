package carp.dsp.core.application.execution.workspace

import dk.cachet.carp.analytics.application.execution.workspace.ExecutionWorkspace
import dk.cachet.carp.analytics.application.execution.workspace.WorkspaceManager
import dk.cachet.carp.analytics.application.plan.ExecutionPlan
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
     * Creates a new execution workspace for the given execution plan and run ID.
     *
     * This is a legacy method that creates a simple workspace based on runId only.
     * For plan-based workspace creation, use PlanBasedWorkspaceManager instead.
     *
     * @param plan The execution plan (used for validation but not for workspace structure)
     * @param runId Unique identifier for the execution run
     * @return A new ExecutionWorkspace instance
     */
    override fun create(plan: ExecutionPlan, runId: UUID): ExecutionWorkspace {
        return createWorkspace(runId)
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
    private fun createWorkspace(runId: UUID): ExecutionWorkspace {
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
     * Resolves a relative artefact path to an absolute filesystem path within the workspace.
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
     * Resolves and validates that a declared artifact path is within the step's outputs directory.
     *
     * This method enforces the critical rule that all declared artifacts must be placed
     * within the step's outputs directory to ensure proper isolation and prevent
     * cross-step contamination.
     *
     * @param workspace The execution workspace
     * @param stepId The step identifier UUID
     * @param relativePath The relative artefact path declared by the step
     * @return The absolute Path to the artefact within the step's outputs directory
     * @throws SecurityException if the path attempts to escape the execution root
     * @throws IllegalArgumentException if the resolved path is not within the step's outputs directory
     */
    fun resolveStepOutputArtifact(workspace: ExecutionWorkspace, stepId: UUID, relativePath: String): Path {
        val executionRoot = pathResolver.resolveExecutionRoot(workspace.executionRoot)
        val outputsDir = executionRoot.resolve(workspace.stepOutputsDir(stepId))

        // First, resolve the path safely within the execution root
        val resolvedPath = pathResolver.validateAndResolve(executionRoot, relativePath)

        // Then enforce that it must be within the step's outputs directory
        if (!pathResolver.isContainedWithin(outputsDir, resolvedPath)) {
            throw IllegalArgumentException(
                "Declared artifact path '$relativePath' must resolve within step outputs directory. " +
                "Resolved: $resolvedPath, Required parent: $outputsDir"
            )
        }

        return resolvedPath
    }

    /**
     * Returns the absolute filesystem path for a step's working directory.
     *
     * Implements [WorkspaceManager.resolveStepWorkingDir] using the known [baseWorkspaceRoot].
     */
    override fun resolveStepWorkingDir(workspace: ExecutionWorkspace, stepId: UUID): String {
        val executionRoot = pathResolver.resolveExecutionRoot(workspace.executionRoot)
        return executionRoot.resolve(workspace.stepDir(stepId)).toString()
    }
}
