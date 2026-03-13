package carp.dsp.core.infrastructure.execution

import carp.dsp.core.infrastructure.runtime.JvmCommandRunner
import dk.cachet.carp.analytics.application.execution.ArtefactStore
import dk.cachet.carp.analytics.application.execution.ExecutionIssue
import dk.cachet.carp.analytics.application.execution.ExecutionIssueKind
import dk.cachet.carp.analytics.application.execution.ExecutionReport
import dk.cachet.carp.analytics.application.execution.ExecutionStatus
import dk.cachet.carp.analytics.application.execution.PlanExecutor
import dk.cachet.carp.analytics.application.execution.RunPolicy
import dk.cachet.carp.analytics.application.execution.StepRunResult
import dk.cachet.carp.analytics.application.execution.workspace.ExecutionWorkspace
import dk.cachet.carp.analytics.application.execution.workspace.WorkspaceManager
import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import dk.cachet.carp.analytics.application.plan.PlannedStep
import dk.cachet.carp.analytics.application.runtime.CommandRunner
import dk.cachet.carp.common.application.UUID
import kotlinx.datetime.Clock

/**
 * Implementation of [PlanExecutor] that drives real command execution.
 *
 * Step execution order is delegated to a [StepOrderStrategy]; the default
 * [SequentialPlanOrder] follows the topological order already produced by the planner.
 *
 * Each step is handed to a [CommandStepRunner] which prepares directories, runs the command
 * via [commandRunner], and maps the result to a [StepRunResult]. The step's working directory
 * is resolved by the [workspaceManager] itself via [WorkspaceManager.resolveStepWorkingDir],
 * so no separate base-root path is needed here.
 * When [RunPolicy.stopOnFailure] is true any failed step causes remaining steps to
 * be recorded as [ExecutionStatus.SKIPPED].
 *
 * @param workspaceManager  Materializes the run workspace on disk and resolves step paths.
 * @param artefactStore     Stores metadata about produced outputs/artifacts.
 * @param commandRunner     Underlying OS-process driver. Defaults to [JvmCommandRunner].
 * @param stepOrderStrategy Controls the execution order of steps. Defaults to [SequentialPlanOrder].
 * @param outputValidationPolicy Controls post-execution output checks. Defaults to [OutputValidationPolicy.DEFAULT].
 * @param clock             Wall-clock source used by [CommandStepRunner]. Defaults to [Clock.System].
 */
class DefaultPlanExecutor(
    private val workspaceManager: WorkspaceManager,
    private val artefactStore: ArtefactStore,
    private val commandRunner: CommandRunner = JvmCommandRunner(),
    private val stepOrderStrategy: StepOrderStrategy = SequentialPlanOrder,
    private val outputValidationPolicy: OutputValidationPolicy = OutputValidationPolicy.DEFAULT,
    private val clock: Clock = Clock.System
) : PlanExecutor {

    /**
     * Executes all steps in [plan] and returns a completed [ExecutionReport].
     *
     * 1. Creates a workspace via [workspaceManager].
     * 2. Resolves execution order via [stepOrderStrategy] (default: planner order).
     * 3. Runs each step via [CommandStepRunner], collecting [StepRunResult]s and [ExecutionIssue]s.
     *    - If a step ID returned by the order strategy is not present in the plan, a
     *      [ExecutionStatus.FAILED] result and an [ExecutionIssueKind.ORCHESTRATOR_ERROR] issue
     *      are recorded and (when [RunPolicy.stopOnFailure] is true) execution is halted.
     * 4. Derives overall [ExecutionStatus] from the step results.
     */
    override fun execute(
        plan: ExecutionPlan,
        runId: UUID,
        policy: RunPolicy
    ): ExecutionReport {
        val workspace = workspaceManager.create(plan, runId)
        val stepOrder = stepOrderStrategy.order(plan)
        val stepsById: Map<UUID, PlannedStep> = plan.steps.associateBy { it.stepId }
        val stepRunner = createStepRunner()
        val stepResults = mutableListOf<StepRunResult>()
        val runIssues = mutableListOf<ExecutionIssue>()
        var halted = false

        for (stepId in stepOrder) {
            val step = stepsById[stepId]
            if (step == null) {
                halted = handleUnknownStep(stepId, policy, stepResults, runIssues, halted)
                continue
            }

            if (halted) {
                recordSkippedStep(stepId, stepResults)
                continue
            }

            val result = runKnownStep(step, stepId, workspace, policy, stepRunner, stepResults, runIssues)
            if (result.status == ExecutionStatus.FAILED && policy.stopOnFailure) {
                halted = true
            }
        }

        return buildExecutionReport(plan, runId, stepResults, runIssues)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun createStepRunner(): CommandStepRunner =
        CommandStepRunner(
            workspaceManager,
            commandRunner,
            artefactStore,
            FileSystemArtefactRecorder(),
            outputValidationPolicy,
            clock
        )

    private fun handleUnknownStep(
        stepId: UUID,
        policy: RunPolicy,
        stepResults: MutableList<StepRunResult>,
        runIssues: MutableList<ExecutionIssue>,
        halted: Boolean
    ): Boolean {
        runIssues += ExecutionIssue(
            stepId = stepId,
            kind = ExecutionIssueKind.ORCHESTRATOR_ERROR,
            message = "Step order strategy referenced unknown step id '$stepId'."
        )
        stepResults += StepRunResult(
            stepId = stepId,
            status = ExecutionStatus.FAILED,
            startedAt = null,
            finishedAt = null,
            outputs = emptyList()
        )

        return halted || policy.stopOnFailure
    }

    private fun recordSkippedStep(stepId: UUID, stepResults: MutableList<StepRunResult>) {
        stepResults += StepRunResult(
            stepId = stepId,
            status = ExecutionStatus.SKIPPED,
            startedAt = null,
            finishedAt = null,
            outputs = emptyList()
        )
    }

    private fun runKnownStep(
        step: PlannedStep,
        stepId: UUID,
        workspace: ExecutionWorkspace,
        policy: RunPolicy,
        stepRunner: CommandStepRunner,
        stepResults: MutableList<StepRunResult>,
        runIssues: MutableList<ExecutionIssue>
    ): StepRunResult {
        val result = stepRunner.run(step, workspace, policy)
        stepResults += result
        runIssues += stepRunner.drainIssues(stepId)
        return result
    }

    private fun buildExecutionReport(
        plan: ExecutionPlan,
        runId: UUID,
        stepResults: List<StepRunResult>,
        runIssues: List<ExecutionIssue>
    ): ExecutionReport {
        val overallStatus = deriveOverallStatus(stepResults)

        return ExecutionReport(
            runId = runId,
            planId = UUID.parse(plan.planId),
            startedAt = stepResults.firstOrNull { it.startedAt != null }?.startedAt,
            finishedAt = stepResults.lastOrNull { it.finishedAt != null }?.finishedAt,
            status = overallStatus,
            stepResults = stepResults,
            issues = runIssues
        )
    }


    private fun deriveOverallStatus(results: List<StepRunResult>): ExecutionStatus =
        when {
            results.any { it.status == ExecutionStatus.FAILED } -> ExecutionStatus.FAILED
            results.any { it.status == ExecutionStatus.SKIPPED } -> ExecutionStatus.FAILED
            results.all { it.status == ExecutionStatus.SUCCEEDED } -> ExecutionStatus.SUCCEEDED
            else -> ExecutionStatus.FAILED
        }
}
