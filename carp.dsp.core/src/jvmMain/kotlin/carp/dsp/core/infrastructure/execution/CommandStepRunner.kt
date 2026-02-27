package carp.dsp.core.infrastructure.execution

import carp.dsp.core.application.execution.CommandPolicy
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
    private val outputValidationPolicy: OutputValidationPolicy = OutputValidationPolicy.DEFAULT,
    private val clock: Clock = Clock.System
) {

    /**
     * Runs [step] inside [workspace] according to [policy] and returns a complete [StepRunResult].
     *
     * Steps whose [PlannedStep.process] is not a [CommandSpec] are immediately failed with
     * [FailureKind.INFRASTRUCTURE] — they cannot be handled by this runner.
     */
    fun run(
        step: PlannedStep,
        workspace: ExecutionWorkspace,
        policy: RunPolicy = CommandPolicy()
    ): StepRunResult {
        // Ensure the step's input/output/log directories exist on disk.
        workspaceManager.prepareStepDirectories(workspace, step.stepId)

        return when (val process = step.process) {
            is CommandSpec -> runCommand(step, process, workspace, policy)
            is InTasksRun -> unsupportedProcess(step, process)
            else -> unsupportedProcess(step, step.process)
        }
    }

    // -------------------------------------------------------------------------
    // CommandSpec execution
    // -------------------------------------------------------------------------

    private fun runCommand(
        step: PlannedStep,
        spec: CommandSpec,
        workspace: ExecutionWorkspace,
        policy: RunPolicy
    ): StepRunResult {
        val startedAt: Instant = clock.now()
        val absWorkingDir = workspaceManager.resolveStepWorkingDir(workspace, step.stepId)
            ?.let { Paths.get(it) }

        val (status, failure, detail) = executeCommand(step, spec, workspace, policy, absWorkingDir)
        val finishedAt: Instant = clock.now()

        val validation = validateOutputs(step, status, absWorkingDir)
        val outputs = validation?.producedOutputRefs ?: emptyList()
        val validationIssues = validation?.issues ?: emptyList()
        val finalStatus = validation?.forcedStatus ?: status
        val finalFailure = if (validation?.forcedStatus != null) validation.failure else failure

        return StepRunResult(
            stepId = step.stepId,
            status = finalStatus,
            startedAt = startedAt,
            finishedAt = finishedAt,
            outputs = outputs,
            failure = finalFailure,
            detail = detail.copy(metrics = null)
        ).also { _pendingIssues[step.stepId] = validationIssues }
    }

    /** Runs the command and maps the raw result to status, failure, and run detail. */
    private data class CommandOutcome(
        val status: ExecutionStatus,
        val failure: StepFailure?,
        val detail: StepRunDetail
    )

    private fun executeCommand(
        step: PlannedStep,
        spec: CommandSpec,
        workspace: ExecutionWorkspace,
        policy: RunPolicy,
        absWorkingDir: java.nio.file.Path?
    ): CommandOutcome {
        val result = if (absWorkingDir != null && commandRunner is JvmCommandRunner) {
            commandRunner.run(spec, policy, absWorkingDir)
        } else {
            commandRunner.run(spec, policy)
        }

        val status = if (result.timedOut || result.exitCode != 0) ExecutionStatus.FAILED
                     else ExecutionStatus.SUCCEEDED

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

        val detail = StepRunDetail(
            command = listOf(spec.executable) + spec.args,
            workingDirectory = workspace.stepDir(step.stepId),
            exitCode = result.exitCode,
            stdout = inlineRef(result.stdout),
            stderr = inlineRef(result.stderr)
        )

        return CommandOutcome(status, failure, detail)
    }

    /** Runs post-step output validation; returns null when skipped. */
    private fun validateOutputs(
        step: PlannedStep,
        status: ExecutionStatus,
        absWorkingDir: java.nio.file.Path?
    ): ValidationResult? {
        if (status != ExecutionStatus.SUCCEEDED || absWorkingDir == null) return null
        return StepOutputValidator.validate(
            stepId = step.stepId,
            outputsDir = absWorkingDir.resolve("outputs"),
            bindings = step.bindings,
            policy = outputValidationPolicy
        )
    }

    private val _pendingIssues = mutableMapOf<UUID, List<ExecutionIssue>>()

    /** Drains and returns any [ExecutionIssue]s recorded during the last run of [stepId]. */
    fun drainIssues(stepId: UUID): List<ExecutionIssue> =
        _pendingIssues.remove(stepId) ?: emptyList()

    // -------------------------------------------------------------------------
    // Unsupported process types
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun inlineRef(text: String): ResourceRef? =
        if (text.isBlank()) null
        else ResourceRef(kind = ResourceKind.URI, value = "data:text/plain,${text.trim()}")
}
