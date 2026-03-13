package carp.dsp.core.infrastructure.execution

import carp.dsp.core.application.execution.StepRunner
import carp.dsp.core.infrastructure.runtime.JvmCommandRunner
import dk.cachet.carp.analytics.application.execution.*
import dk.cachet.carp.analytics.application.execution.workspace.ExecutionWorkspace
import dk.cachet.carp.analytics.application.execution.workspace.WorkspaceManager
import dk.cachet.carp.analytics.application.plan.CommandSpec
import dk.cachet.carp.analytics.application.plan.InTasksRun
import dk.cachet.carp.analytics.application.plan.PlannedStep
import dk.cachet.carp.analytics.application.runtime.CommandRunner
import dk.cachet.carp.common.application.UUID
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.nio.file.Paths

/**
 * Runs a single [PlannedStep] against a live [ExecutionWorkspace].
 *
 * After the command finishes, [StepOutputValidator] is called to check declared outputs
 * against what actually exists on disk. Issues are returned on [StepRunResult] and the
 * step status may be escalated to FAILED when [OutputValidationPolicy.strictOutputs] is true.
 *
 * @param workspaceManager    Prepares per-step directories and resolves absolute paths.
 * @param commandRunner       The underlying OS-process driver.
 * @param outputValidationPolicy Controls post-execution output checks.
 * @param clock               Source of wall-clock [Instant]s; defaults to [Clock.System].
 */
class CommandStepRunner(
    private val workspaceManager: WorkspaceManager,
    private val commandRunner: CommandRunner,
    private val artefactStore: ArtefactStore,
    private val artefactRecorder: ArtefactRecorder = FileSystemArtefactRecorder(),
    private val outputValidationPolicy: OutputValidationPolicy = OutputValidationPolicy.DEFAULT,
    private val clock: Clock = Clock.System
) : StepRunner {

    /**
     * Runs [step] inside [workspace] according to [policy] and returns a complete [StepRunResult].
     *
     * Steps whose [PlannedStep.process] is not a [CommandSpec] are immediately failed with
     * [FailureKind.INFRASTRUCTURE] — they cannot be handled by this runner.
     */
    override fun run(
        step: PlannedStep,
        workspace: ExecutionWorkspace,
        policy: RunPolicy
    ): StepRunResult {
        // Ensure the step's input/output/log directories exist on disk.
        workspaceManager.prepareStepDirectories(workspace, step.stepId)

        return when (val process = step.process) {
            is CommandSpec -> runCommand(step, process, workspace, policy)
            is InTasksRun -> unsupportedProcess(step, process)
            else -> unsupportedProcess(step, step.process)
        }
    }

    // CommandSpec Execution (Template Method Pattern)

    /**
     * Template method for CommandSpec execution.
     *
     * Orchestrates: prepare → execute → validate → record
     */
    private fun runCommand(
        step: PlannedStep,
        spec: CommandSpec,
        workspace: ExecutionWorkspace,
        policy: RunPolicy
    ): StepRunResult {
        val startedAt = clock.now()
        val absWorkingDir = workspaceManager.resolveStepWorkingDir(workspace, step.stepId)
            ?.let { Paths.get(it) }

        // Step 1: Execute command
        val (status, failure, detail) = executeCommand(step, spec, workspace, policy, absWorkingDir)

        // Step 2: Validate outputs (post-execution check)
        val validation = validateOutputs(step, status, absWorkingDir)
        val finalStatus = validation?.forcedStatus ?: status
        val finalFailure = if (validation?.forcedStatus != null) validation.failure else failure

        // Step 3: Record artefacts (only if succeeded)
        val producedArtifacts = if (finalStatus == ExecutionStatus.SUCCEEDED && absWorkingDir != null) {
            artefactRecorder.recordArtefacts(step, absWorkingDir, artefactStore)
        } else {
            emptyList()
        }

        // Step 4: Collect issues
        val validationIssues = validation?.issues ?: emptyList()
        _pendingIssues[step.stepId] = validationIssues

        // Return final result
        return StepRunResult(
            stepId = step.stepId,
            status = finalStatus,
            startedAt = startedAt,
            finishedAt = clock.now(),
            outputs = producedArtifacts,
            failure = finalFailure,
            detail = detail
        )
    }

    // Command Execution

    /**
     * Internal result of command execution.
     *
     * Contains everything needed to create StepRunResult.
     */
    private data class CommandOutcome(
        val status: ExecutionStatus,
        val failure: StepFailure?,
        val detail: StepRunDetail
    )

    /**
     * Execute the command and map result to status/failure/detail.
     */
    private fun executeCommand(
        step: PlannedStep,
        spec: CommandSpec,
        workspace: ExecutionWorkspace,
        policy: RunPolicy,
        absWorkingDir: java.nio.file.Path?
    ): CommandOutcome {
        // Run the command (with working directory if available)
        val result = if (absWorkingDir != null && commandRunner is JvmCommandRunner) {
            commandRunner.run(spec, policy, absWorkingDir)
        } else {
            commandRunner.run(spec, policy)
        }

        // Map exit code to status
        val status = if (result.timedOut || result.exitCode != 0) {
            ExecutionStatus.FAILED
        } else {
            ExecutionStatus.SUCCEEDED
        }

        // Create failure if needed
        val failure: StepFailure? = when {
            result.timedOut -> StepFailure(
                FailureKind.TIMEOUT,
                "Step '${step.name}' timed out after ${policy.timeoutMs} ms"
            )
            result.exitCode != 0 -> StepFailure(
                FailureKind.COMMAND_FAILED,
                "Step '${step.name}' exited with code ${result.exitCode}"
            )
            else -> null
        }

        // Create run detail with human-readable output
        val detail = StepRunDetail(
            command = listOf(spec.executable) + spec.args.toResolvedStrings(),
            workingDirectory = workspace.stepDir(step.stepId),
            exitCode = result.exitCode,
            stdout = inlineRef(result.stdout),
            stderr = inlineRef(result.stderr)
        )

        return CommandOutcome(status, failure, detail)
    }

    // Output Validation

    /**
     * Validate declared outputs against actual files on disk.
     *
     * Returns null if validation is skipped (not succeeded or no working dir).
     */
    private fun validateOutputs(
        step: PlannedStep,
        status: ExecutionStatus,
        absWorkingDir: java.nio.file.Path?
    ): ValidationResult? {
        if (status != ExecutionStatus.SUCCEEDED || absWorkingDir == null) {
            return null
        }
        return StepOutputValidator.validate(
            stepId = step.stepId,
            outputsDir = absWorkingDir.resolve("outputs"),
            bindings = step.bindings,
            policy = outputValidationPolicy
        )
    }

    // Issue Tracking

    private val _pendingIssues = mutableMapOf<UUID, List<ExecutionIssue>>()

    /**
     * Drain issues recorded during last execution of [stepId].
     *
     * Used by orchestrators to collect all issues from a run.
     */
    fun drainIssues(stepId: UUID): List<ExecutionIssue> =
        _pendingIssues.remove(stepId) ?: emptyList()

    // Unsupported Process Types

    /**
     * Called when step process type is not CommandSpec.
     *
     * Returns FAILED with clear INFRASTRUCTURE error.
     */
    private fun unsupportedProcess(step: PlannedStep, process: Any): StepRunResult {
        val startedAt = clock.now()
        return StepRunResult(
            stepId = step.stepId,
            status = ExecutionStatus.FAILED,
            startedAt = startedAt,
            finishedAt = startedAt,
            outputs = emptyList(),
            failure = StepFailure(
                kind = FailureKind.INFRASTRUCTURE,
                message = "CommandStepRunner cannot handle process type " +
                        "'${process::class.simpleName}' for step '${step.name}'"
            )
        )
    }

    // Helpers

    /**
     * Create ResourceRef for inline stdout/stderr.
     *
     * For empty output, returns null (no log to record).
     * For non-empty, uses data: URI for inline storage.
     */
    private fun inlineRef(text: String): ResourceRef? =
        if (text.isBlank()) null
        else ResourceRef(
            kind = ResourceKind.URI,
            value = "data:text/plain,${text.trim()}"
        )
}

