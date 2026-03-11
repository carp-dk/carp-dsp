package carp.dsp.core.infrastructure.execution

import dk.cachet.carp.analytics.application.execution.workspace.ExecutionWorkspace
import dk.cachet.carp.analytics.application.execution.workspace.WorkspaceManager
import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import dk.cachet.carp.common.application.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories


private const val PLAN_HASH_BYTES = 8

/**
 * Infrastructure implementation of [WorkspaceManager] that creates workspaces based on ExecutionPlan structure.
 *
 * This implementation:
 * - Computes deterministic plan hashes based on ExecutionPlan structure (excluding runId)
 * - Creates workspace directory layouts using planHash and runId
 * - Materializes directory structures for all planned steps
 * - Prevents path traversal and validates directory creation
 *
 * @param baseWorkspaceRoot The base directory where all execution workspaces will be created
 */
class PlanBasedWorkspaceManager(
    private val baseWorkspaceRoot: Path
) : WorkspaceManager {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // Ensure stable serialization for consistent hashing
        prettyPrint = false
    }

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
     * The workspace structure created follows the layout:
     * ```
     * baseWorkspaceRoot/
     *   {planHash}_{runId}/          # executionRoot
     *     steps/
     *       {stepId}/
     *         inputs/
     *         outputs/
     *         logs/
     * ```
     *
     * @param plan The execution plan containing workflow and step information
     * @param runId Unique identifier for the execution run
     * @return A new ExecutionWorkspace instance
     * @throws IllegalStateException if directory creation fails
     */
    override fun create(plan: ExecutionPlan, runId: UUID): ExecutionWorkspace {
        // 1. Compute deterministic plan hash
        val planHash = computePlanHash(plan)

        // 2. Compute execution root directory name
        val executionRootName = "${planHash}_$runId"

        // 3. Create directory structure
        val executionRootPath = baseWorkspaceRoot.resolve(executionRootName)

        try {
            // Create the execution root directory
            executionRootPath.createDirectories()

            // Create the steps directory
            val stepsPath = executionRootPath.resolve("steps")
            stepsPath.createDirectories()

            // Create directories for all planned steps
            plan.steps.forEach { plannedStep ->
                createStepDirectoryStructure(stepsPath, plannedStep.stepId)
            }

            return ExecutionWorkspace(
                runId = runId,
                executionRoot = executionRootName
            )
        } catch (e: IOException) {
            throw IllegalStateException(
                "Failed to create workspace for plan ${plan.planId} with run $runId at $executionRootPath",
                e
            )
        }
    }

    /**
     * Prepares the directory structure for a specific step within the workspace.
     *
     * This implementation assumes the step directories are already created during workspace creation,
     * but this method can be used to ensure directories exist if called independently.
     *
     * @param workspace The execution workspace
     * @param stepId The step identifier UUID
     * @throws IllegalStateException if directory creation fails
     */
    override fun prepareStepDirectories(workspace: ExecutionWorkspace, stepId: UUID) {
        val executionRootPath = baseWorkspaceRoot.resolve(workspace.executionRoot)
        val stepsPath = executionRootPath.resolve("steps")

        try {
            createStepDirectoryStructure(stepsPath, stepId)
        } catch (e: IOException) {
            throw IllegalStateException(
                "Failed to create step directories for step $stepId in workspace ${workspace.runId}",
                e
            )
        }
    }

    /**
     * Returns the absolute filesystem path for a step's working directory.
     *
     * Implements [WorkspaceManager.resolveStepWorkingDir] using the known [baseWorkspaceRoot].
     */
    override fun resolveStepWorkingDir(workspace: ExecutionWorkspace, stepId: UUID): String =
        baseWorkspaceRoot
            .resolve(workspace.executionRoot)
            .resolve(workspace.stepDir(stepId))
            .toAbsolutePath()
            .normalize()
            .toString()

    /**
     * Computes a deterministic hash for the ExecutionPlan structure.
     *
     * The hash is computed based only on the plan structure and content, excluding:
     * - runId (not part of ExecutionPlan)
     * - planId (maybe a random UUID that changes between planning runs)
     * - Execution-specific runtime data
     * - TasksRun polymorphic content (to avoid serialization complexity)
     *
     * Uses stable JSON serialization and SHA-256 hashing.
     *
     * @param plan The execution plan to hash
     * @return A short hex string representing the plan hash
     */
    private fun computePlanHash(plan: ExecutionPlan): String {
        // Create a simplified representation for hashing that avoids polymorphic serialization
        val hashableContent = SimplifiedPlanHashContent(
            workflowId = plan.workflowId,
            // Note: planId is intentionally excluded as it may be a random UUID generated at planning time
            steps = plan.steps.sortedBy { it.stepId.toString() }.map { step ->
                SimplifiedStepHashContent(
                    stepId = step.stepId.toString(),
                    name = step.name,
                    environmentDefinitionId = step.environmentRef.toString(),
                    // Note: We intentionally exclude the TasksRun process field to avoid polymorphic serialization
                    // The step ID and name provide sufficient differentiation for workspace hashing
                    inputBindings = step.bindings.inputs.keys.sortedBy { it.toString() }.map { it.toString() },
                    outputBindings = step.bindings.outputs.keys.sortedBy { it.toString() }.map { it.toString() }
                )
            },
            requiredEnvironmentHandles = plan.requiredEnvironmentRefs.keys.sortedBy
            {
                it.toString()
            }.map { it.toString() }
            // Note: deliberately excluding 'issues' as they may vary between planning runs
        )

        // Serialize to JSON with stable ordering
        val jsonString = json.encodeToString(SimplifiedPlanHashContent.serializer(), hashableContent)

        // Compute SHA-256 hash
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(jsonString.toByteArray(Charsets.UTF_8))

        // Return first 8 bytes as hex (16 characters) for reasonable uniqueness with readability
        return hashBytes.take(PLAN_HASH_BYTES).joinToString("") { "%02x".format(it) }
    }

    /**
     * Creates the standard directory structure for a step.
     *
     * @param stepsPath The parent steps directory
     * @param stepId The step identifier UUID
     */
    private fun createStepDirectoryStructure(stepsPath: Path, stepId: UUID) {
        val stepPath = stepsPath.resolve(stepId.toString())
        val inputsPath = stepPath.resolve("inputs")
        val outputsPath = stepPath.resolve("outputs")
        val logsPath = stepPath.resolve("logs")

        stepPath.createDirectories()
        inputsPath.createDirectories()
        outputsPath.createDirectories()
        logsPath.createDirectories()
    }
}

/**
 * Simplified data class representing the hashable content of an ExecutionPlan.
 *
 * This avoids polymorphic serialization issues by using only simple types.
 * The planId is deliberately excluded as it may be a random UUID that changes between planning runs.
 */
@Serializable
private data class SimplifiedPlanHashContent(
    val workflowId: String,
    val steps: List<SimplifiedStepHashContent>,
    val requiredEnvironmentHandles: List<String>
)

/**
 * Simplified data class representing the hashable content of a PlannedStep.
 *
 * This excludes the polymorphic TasksRun field to avoid serialization complexity.
 */
@Serializable
private data class SimplifiedStepHashContent(
    val stepId: String,
    val name: String,
    val environmentDefinitionId: String,
    val inputBindings: List<String>,
    val outputBindings: List<String>
)

