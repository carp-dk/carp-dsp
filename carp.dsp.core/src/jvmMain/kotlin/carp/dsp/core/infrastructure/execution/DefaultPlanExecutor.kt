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
import dk.cachet.carp.analytics.application.plan.CommandSpec
import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import dk.cachet.carp.analytics.application.plan.ExpandedArg
import dk.cachet.carp.analytics.application.plan.PlannedStep
import dk.cachet.carp.analytics.application.runtime.CommandRunner
import dk.cachet.carp.analytics.infrastructure.execution.EnvironmentConfig
import dk.cachet.carp.analytics.infrastructure.execution.EnvironmentExecutionLogs
import dk.cachet.carp.analytics.infrastructure.execution.EnvironmentOrchestrator
import dk.cachet.carp.analytics.infrastructure.execution.SetupTiming
import dk.cachet.carp.common.application.UUID
import kotlinx.datetime.Clock
import java.nio.file.Paths

/**
 * Implementation of [PlanExecutor] that drives real command execution.
 *
 * Step execution order is delegated to a [StepOrderStrategy]; the default
 * [SequentialPlanOrder] follows the topological order already produced by the planner.
 *
 * Each step is handed to a [CommandStepRunner] which prepares directories, runs the command
 * via [CommandStepRunner], and maps the result to a [StepRunResult]. The step's working directory
 * is resolved by the [workspaceManager] itself via [WorkspaceManager.resolveStepWorkingDir],
 * so no separate base-root path is needed here.
 * When [RunPolicy.stopOnFailure] is true any failed step causes remaining steps to
 * be recorded as [ExecutionStatus.SKIPPED].
 *
 * @param workspaceManager  Materializes the run workspace on disk and resolves step paths.
 * @param artefactStore     Stores metadata about produced outputs/artefacts.
 * @param options           Optional execution dependencies (runner, ordering, validation, orchestrator, config, clock).
 */
class DefaultPlanExecutor(
    private val workspaceManager: WorkspaceManager,
    private val artefactStore: ArtefactStore,
    private val options: Options = Options()
) : PlanExecutor {

    /**
     * Executes all steps in [plan] and returns a completed [ExecutionReport].
     *
     * 1. Creates a workspace via [workspaceManager].
     * 2. Resolves execution order via [StepOrderStrategy] (default: planner order).
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
        val stepOrder = options.stepOrderStrategy.order(plan)
        val stepsById: Map<UUID, PlannedStep> = plan.steps.associateBy { it.stepId }
        val stepRunner = createStepRunner()
        val environmentCoordinator = EnvironmentExecutionCoordinator(
            plan = plan,
            orchestrator = options.orchestrator,
            config = options.environmentConfig
        )
        val stepResults = mutableListOf<StepRunResult>()
        val runIssues = mutableListOf<ExecutionIssue>()
        val knownStepContext = KnownStepExecutionContext(
            workspace = workspace,
            policy = policy,
            stepRunner = stepRunner,
            environmentCoordinator = environmentCoordinator,
            stepResults = stepResults,
            runIssues = runIssues
        )
        var halted = false
        try {
            if (environmentCoordinator.setupEagerEnvironments(runIssues) && policy.stopOnFailure) {
                halted = true
            }

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

                val result = runKnownStep(step, knownStepContext)
                if (result.status == ExecutionStatus.FAILED && policy.stopOnFailure) {
                    halted = true
                }
            }
        } finally {
            environmentCoordinator.teardownAll(runIssues)
        }

        return buildExecutionReport(
            plan = plan,
            runId = runId,
            stepResults = stepResults,
            runIssues = runIssues,
            environmentLogs = environmentCoordinator.environmentLogs()
        )
    }

    // Helpers

    private fun createStepRunner(): CommandStepRunner =
        CommandStepRunner(
            workspaceManager = workspaceManager,
            commandRunner = options.commandRunner,
            artefactStore = artefactStore,
            options = CommandStepRunner.Options(
                artefactRecorder = FileSystemArtefactRecorder(),
                logRecorder = FileSystemStepLogRecorder(),
                outputValidationPolicy = options.outputValidationPolicy,
                clock = options.clock
            )
        )

    /**
     * Optional dependencies and defaults used by [DefaultPlanExecutor].
     * Groups constructor parameters to keep the primary signature small.
     */
    data class Options(
        val commandRunner: CommandRunner = JvmCommandRunner(),
        val stepOrderStrategy: StepOrderStrategy = SequentialPlanOrder,
        val outputValidationPolicy: OutputValidationPolicy = OutputValidationPolicy.DEFAULT,
        val orchestrator: EnvironmentOrchestrator = DefaultEnvironmentOrchestrator(
            DefaultEnvironmentRegistry(
                Paths.get(System.getProperty("java.io.tmpdir"), "carp-dsp-environment-registry.json")
            )
        ),
        val environmentConfig: EnvironmentConfig = EnvironmentConfig(),
        val clock: Clock = Clock.System
    )

    private data class KnownStepExecutionContext(
        val workspace: ExecutionWorkspace,
        val policy: RunPolicy,
        val stepRunner: CommandStepRunner,
        val environmentCoordinator: EnvironmentExecutionCoordinator,
        val stepResults: MutableList<StepRunResult>,
        val runIssues: MutableList<ExecutionIssue>
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
        context: KnownStepExecutionContext
    ): StepRunResult {
        val preparedStep = context.environmentCoordinator.prepareStep(step, context.runIssues)
        if (preparedStep == null) {
            val failedResult = StepRunResult(
                stepId = step.stepId,
                status = ExecutionStatus.FAILED,
                startedAt = null,
                finishedAt = null,
                outputs = emptyList()
            )
            context.stepResults += failedResult
            return failedResult
        }

        val result = context.stepRunner.run(preparedStep, context.workspace, context.policy)
        context.stepResults += result
        context.runIssues += context.stepRunner.drainIssues(step.stepId)
        return result
    }

    private fun buildExecutionReport(
        plan: ExecutionPlan,
        runId: UUID,
        stepResults: List<StepRunResult>,
        runIssues: List<ExecutionIssue>,
        environmentLogs: EnvironmentExecutionLogs
    ): ExecutionReport {
        val overallStatus = deriveOverallStatus(stepResults)

        return ExecutionReport(
            runId = runId,
            planId = UUID.parse(plan.planId),
            startedAt = stepResults.firstOrNull { it.startedAt != null }?.startedAt,
            finishedAt = stepResults.lastOrNull { it.finishedAt != null }?.finishedAt,
            status = overallStatus,
            stepResults = stepResults,
            issues = runIssues,
            environmentLogs = environmentLogs
        )
    }


    private fun deriveOverallStatus(results: List<StepRunResult>): ExecutionStatus =
        when {
            results.any { it.status == ExecutionStatus.FAILED } -> ExecutionStatus.FAILED
            results.any { it.status == ExecutionStatus.SKIPPED } -> ExecutionStatus.FAILED
            results.all { it.status == ExecutionStatus.SUCCEEDED } -> ExecutionStatus.SUCCEEDED
            else -> ExecutionStatus.FAILED
        }

    /**
     * Coordinates environment lifecycle and command wrapping for each step.
     */
    private class EnvironmentExecutionCoordinator(
        private val plan: ExecutionPlan,
        private val orchestrator: EnvironmentOrchestrator,
        private val config: EnvironmentConfig
    ) {
        private val setupEnvironments = mutableSetOf<String>()
        private val requiredRefs = plan.requiredEnvironmentRefs.values.distinctBy { it.id }

        fun setupEagerEnvironments(runIssues: MutableList<ExecutionIssue>): Boolean {
            if (config.setupTiming != SetupTiming.EAGER) return false

            var failed = false
            for (environmentRef in requiredRefs) {
                if (!ensureSetup(environmentRef.id, runIssues)) {
                    failed = true
                }
            }
            return failed
        }

        fun prepareStep(step: PlannedStep, runIssues: MutableList<ExecutionIssue>): PlannedStep? {
            val environmentId = step.environmentRef ?: return step
            if (plan.requiredEnvironmentRefs.isEmpty()) return step

            val environmentRef = plan.requiredEnvironmentRefs[environmentId]
            if (environmentRef == null) {
                runIssues += ExecutionIssue(
                    stepId = step.stepId,
                    kind = ExecutionIssueKind.ORCHESTRATOR_ERROR,
                    message = "No EnvironmentRef mapped for step '${step.name}' ($environmentId)."
                )
                return null
            }

            if (config.setupTiming == SetupTiming.LAZY && !ensureSetup(environmentRef.id, runIssues, step.stepId)) {
                return null
            }

            return wrapStepCommand(step, environmentRef.id, environmentRef, runIssues)
        }

        fun teardownAll(runIssues: MutableList<ExecutionIssue>) {
            for (environmentRef in requiredRefs) {
                val success = runCatching { orchestrator.teardown(environmentRef) }.getOrElse { false }
                if (!success) {
                    runIssues += ExecutionIssue(
                        kind = ExecutionIssueKind.ORCHESTRATOR_ERROR,
                        message = "Failed to teardown environment '${environmentRef.id}'."
                    )
                }
            }
        }

        fun environmentLogs(): EnvironmentExecutionLogs =
            (orchestrator as? DefaultEnvironmentOrchestrator)?.getEnvironmentLogs()
                ?: EnvironmentExecutionLogs()

        private fun ensureSetup(
            environmentId: String,
            runIssues: MutableList<ExecutionIssue>,
            stepId: UUID? = null
        ): Boolean {
            if (setupEnvironments.contains(environmentId)) return true

            val ref = requiredRefs.firstOrNull { it.id == environmentId }
            if (ref == null) {
                runIssues += ExecutionIssue(
                    stepId = stepId,
                    kind = ExecutionIssueKind.ORCHESTRATOR_ERROR,
                    message = "Environment '$environmentId' is not available in requiredEnvironmentRefs."
                )
                return false
            }

            val setupOk = runCatching { orchestrator.setup(ref) }.getOrElse { false }
            if (!setupOk) {
                runIssues += ExecutionIssue(
                    stepId = stepId,
                    kind = ExecutionIssueKind.ORCHESTRATOR_ERROR,
                    message = "Failed to setup environment '${ref.id}'."
                )
                return false
            }

            setupEnvironments += environmentId
            return true
        }

        private fun wrapStepCommand(
            step: PlannedStep,
            environmentId: String,
            environmentRef: dk.cachet.carp.analytics.application.plan.EnvironmentRef,
            runIssues: MutableList<ExecutionIssue>
        ): PlannedStep? {
            val process = step.process
            if (process !is CommandSpec) return step

            val wrappedSpec = runCatching {
                val baseCommand = buildBaseCommand(process)
                val wrapped = orchestrator.generateExecutionCommand(environmentRef, baseCommand)
                toShellCommandSpec(wrapped)
            }.getOrNull()

            if (wrappedSpec == null) {
                runIssues += ExecutionIssue(
                    stepId = step.stepId,
                    kind = ExecutionIssueKind.ORCHESTRATOR_ERROR,
                    message = "Failed to generate execution command for environment '$environmentId'."
                )
                return null
            }

            return step.copy(process = wrappedSpec)
        }

        private fun buildBaseCommand(spec: CommandSpec): String {
            val args = spec.args
                .map { argToLiteral(it) }
                .joinToString(" ") { shellQuote(it) }
            return if (args.isBlank()) spec.executable else "${spec.executable} $args"
        }

        private fun argToLiteral(arg: ExpandedArg): String = when (arg) {
            is ExpandedArg.Literal -> arg.value
            is ExpandedArg.DataReference -> arg.dataRefId.toString()
            is ExpandedArg.PathSubstitution -> arg.template.replace("$()", arg.dataRefId.toString())
        }

        private fun toShellCommandSpec(command: String): CommandSpec {
            val shell = if (isWindows()) "cmd" else "sh"
            val switch = if (isWindows()) "/c" else "-lc"
            return CommandSpec(
                executable = shell,
                args = listOf(ExpandedArg.Literal(switch), ExpandedArg.Literal(command))
            )
        }

        private fun shellQuote(value: String): String {
            if (value.isEmpty()) return if (isWindows()) "\"\"" else "''"
            return if (isWindows()) {
                if (value.any { it.isWhitespace() || it == '"' }) {
                    "\"${value.replace("\"", "\\\"")}\""
                } else {
                    value
                }
            } else {
                if (value.any { it.isWhitespace() || it == '\'' }) {
                    "'${value.replace("'", "'\"'\"'")}'"
                } else {
                    value
                }
            }
        }

        private fun isWindows(): Boolean =
            System.getProperty("os.name")?.contains("win", ignoreCase = true) == true
    }
}
