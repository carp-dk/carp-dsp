package carp.dsp.core.infrastructure.execution

import carp.dsp.core.application.execution.ExecutionLogger
import carp.dsp.core.infrastructure.runtime.JvmCommandRunner
import dk.cachet.carp.analytics.application.exceptions.*
import dk.cachet.carp.analytics.application.execution.*
import dk.cachet.carp.analytics.application.execution.workspace.ExecutionWorkspace
import dk.cachet.carp.analytics.application.execution.workspace.WorkspaceManager
import dk.cachet.carp.analytics.application.plan.*
import dk.cachet.carp.analytics.application.runtime.CommandRunner
import dk.cachet.carp.analytics.domain.workflow.StepMetadata
import dk.cachet.carp.analytics.infrastructure.execution.EnvironmentConfig
import dk.cachet.carp.analytics.infrastructure.execution.EnvironmentExecutionLogs
import dk.cachet.carp.analytics.infrastructure.execution.EnvironmentOrchestrator
import dk.cachet.carp.analytics.infrastructure.execution.SetupTiming
import dk.cachet.carp.common.application.UUID
import io.github.oshai.kotlinlogging.KotlinLogging
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

    private val logger = KotlinLogging.logger {}

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
        logger.info { "Executing plan '${plan.workflowName}' (${plan.steps.size} step(s), runId=$runId)" }
        val (context, environmentCoordinator) = initializeExecutionContext(plan, runId, policy)

        try {
            executeSteps(plan, policy, context, environmentCoordinator)
        } finally {
            environmentCoordinator.teardownAll(context.runIssues)
        }

        return buildExecutionReport(
            plan = plan,
            runId = runId,
            stepResults = context.stepResults,
            runIssues = context.runIssues,
            environmentLogs = environmentCoordinator.environmentLogs()
        )
    }

    private fun initializeExecutionContext(
        plan: ExecutionPlan,
        runId: UUID,
        policy: RunPolicy
    ): Pair<KnownStepExecutionContext, EnvironmentExecutionCoordinator> {
        val workspace = workspaceManager.create(plan, runId)
        val stepRunner = createStepRunner()
        val environmentCoordinator = EnvironmentExecutionCoordinator(
            plan = plan,
            orchestrator = options.orchestrator,
            config = options.environmentConfig
        )
        val stepResults = mutableListOf<StepRunResult>()
        val runIssues = mutableListOf<ExecutionIssue>()
        val context = KnownStepExecutionContext(
            runId = runId,
            workspace = workspace,
            policy = policy,
            stepRunner = stepRunner,
            environmentCoordinator = environmentCoordinator,
            stepResults = stepResults,
            runIssues = runIssues
        )
        return Pair(context, environmentCoordinator)
    }

    private fun executeSteps(
        plan: ExecutionPlan,
        policy: RunPolicy,
        context: KnownStepExecutionContext,
        environmentCoordinator: EnvironmentExecutionCoordinator
    ) {
        val stepOrder = options.stepOrderStrategy.order(plan)
        val stepsById: Map<UUID, PlannedStep> = plan.steps.associateBy { it.metadata.id }

        if (environmentCoordinator.setupEagerEnvironments(context.runIssues) && policy.stopOnFailure) {
            // Record all remaining steps as skipped
            for (stepId in stepOrder) {
                val step = stepsById[stepId]
                if (step != null) {
                    recordSkippedStep(step.metadata, context.stepResults)
                }
            }
            return
        }

        var halted = false
        for (stepId in stepOrder) {
            val step = stepsById[stepId]
            halted = when {
                step == null -> handleUnknownStep(
                    StepMetadata("Unknown step $stepId", id = stepId),
                    policy, context.stepResults, context.runIssues, halted
                )
                halted -> {
                    logger.info { "Skipping step '${step.metadata.name}' (halted)" }
                    recordSkippedStep(step.metadata, context.stepResults)
                    true
                }
                else -> processStep(step, context, policy)
            }
        }
    }

    private fun processStep(
        step: PlannedStep,
        context: KnownStepExecutionContext,
        policy: RunPolicy
    ): Boolean {
        logger.info { "Running step '${step.metadata.name}'" }
        val startMs = System.currentTimeMillis()
        options.executionLogger.onStepStarted(context.runId, step.metadata.id, step.metadata.name)

        val result = executeStepWithFallback(step, context)
        val durationMs = System.currentTimeMillis() - startMs

        return when (result.status) {
            ExecutionStatus.FAILED -> {
                logger.warn { "Step '${step.metadata.name}' failed" }
                options.executionLogger.onStepFailed(
                    context.runId, step.metadata.id, step.metadata.name,
                    result.failure?.message ?: "non-zero exit"
                )
                policy.stopOnFailure
            }
            else -> {
                logger.info { "Step '${step.metadata.name}' succeeded" }
                options.executionLogger.onStepCompleted(
                    context.runId, step.metadata.id, step.metadata.name, durationMs
                )
                false
            }
        }
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
        val clock: Clock = Clock.System,
        val executionLogger: ExecutionLogger = Slf4jExecutionLogger()
    )

    private data class KnownStepExecutionContext(
        val runId: UUID,
        val workspace: ExecutionWorkspace,
        val policy: RunPolicy,
        val stepRunner: CommandStepRunner,
        val environmentCoordinator: EnvironmentExecutionCoordinator,
        val stepResults: MutableList<StepRunResult>,
        val runIssues: MutableList<ExecutionIssue>
    )

    private fun handleUnknownStep(
        stepMetadata: StepMetadata,
        policy: RunPolicy,
        stepResults: MutableList<StepRunResult>,
        runIssues: MutableList<ExecutionIssue>,
        halted: Boolean
    ): Boolean {
        runIssues += ExecutionIssue(
            stepMetadata = stepMetadata,
            kind = ExecutionIssueKind.ORCHESTRATOR_ERROR,
            message = "Step order strategy referenced unknown step id '${stepMetadata.name}' (${stepMetadata.id})."
        )
        stepResults += StepRunResult(
            stepMetadata = stepMetadata,
            status = ExecutionStatus.FAILED,
            startedAt = null,
            finishedAt = null,
            outputs = emptyList()
        )

        return halted || policy.stopOnFailure
    }

    private fun recordSkippedStep(stepMetadata: StepMetadata, stepResults: MutableList<StepRunResult>) {
        stepResults += StepRunResult(
            stepMetadata = stepMetadata,
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
                stepMetadata = step.metadata,
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
        context.runIssues += context.stepRunner.drainIssues(step.metadata.id)
        return result
    }

    /**
     * Executes a step, catching any workflow execution exceptions
     * and converting them to ExecutionIssues.
     *
     * @param step The step to execute
     * @param context Shared execution context with issue tracking
     * @return Either the successful result or a failed result with issue recorded
     */
    private fun executeStepWithFallback(
        step: PlannedStep,
        context: KnownStepExecutionContext
    ): StepRunResult = try {
        runKnownStep(step, context)
    } catch (e: WorkflowExecutionException) {
        handleStepException(step.metadata, e, context)
    }

    /**
     * Converts a WorkflowExecutionException to an ExecutionIssue
     * and returns a failed StepRunResult.
     *
     * Maps exception types to appropriate issue kinds and formats
     * human-readable error messages.
     *
     * @param stepMetadata The metadata of the step that threw the exception
     * @param exception The exception that occurred
     * @param context Shared execution context to record the issue
     * @return A failed StepRunResult documenting the error
     */
    private fun handleStepException(
        stepMetadata: StepMetadata,
        exception: WorkflowExecutionException,
        context: KnownStepExecutionContext
    ): StepRunResult {
        val issueKind = when (exception) {
            is ProcessExecutionException -> ExecutionIssueKind.PROCESS_FAILED
            is EnvironmentSetupException -> ExecutionIssueKind.ORCHESTRATOR_ERROR
            is ExecutionIOException -> ExecutionIssueKind.ORCHESTRATOR_ERROR
            is ArtefactCollectionException -> ExecutionIssueKind.ORCHESTRATOR_ERROR
            else -> ExecutionIssueKind.ORCHESTRATOR_ERROR
        }

        val message = when (exception) {
            is ProcessExecutionException ->
                "Command '${exception.command}' failed with exit code ${exception.exitCode ?: "unknown"}"
            is EnvironmentSetupException ->
                "Failed to setup environment: ${exception.message}"
            is ExecutionIOException ->
                "File I/O error: ${exception.message}"
            is ArtefactCollectionException ->
                "Failed to collect artifacts: ${exception.message}"
            else ->
                "Execution error: ${exception.message ?: "Unknown error"}"
        }

        context.runIssues.add(
            ExecutionIssue(
                stepMetadata = stepMetadata,
                kind = issueKind,
                message = message
            )
        )

        return StepRunResult(
            stepMetadata = stepMetadata,
            status = ExecutionStatus.FAILED,
            startedAt = Clock.System.now(),
            finishedAt = Clock.System.now(),
            outputs = emptyList()
        )
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
                if (!ensureSetup(environmentRef.id, runIssues )) {
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
                    stepMetadata = step.metadata,
                    kind = ExecutionIssueKind.ORCHESTRATOR_ERROR,
                    message = "No EnvironmentRef mapped for step '${step.metadata.name}' ($environmentId)."
                )
                return null
            }

            if (config.setupTiming == SetupTiming.LAZY && !ensureSetup(environmentRef.id, runIssues, step.metadata)) {
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
            stepMetadata: StepMetadata? = null
        ): Boolean {
            if (setupEnvironments.contains(environmentId)) return true

            val ref = requiredRefs.firstOrNull { it.id == environmentId }
            if (ref == null) {
                runIssues += ExecutionIssue(
                    stepMetadata = stepMetadata,
                    kind = ExecutionIssueKind.ORCHESTRATOR_ERROR,
                    message = "Environment '$environmentId' is not available in requiredEnvironmentRefs."
                )
                return false
            }

            val setupResult = runCatching { orchestrator.setup(ref) }
            val setupOk = setupResult.getOrDefault(false)
            if (!setupOk) {
                val ex = setupResult.exceptionOrNull()
                val msg = buildString {
                    append("Failed to setup environment '${ref.id}'")
                    ex?.message?.let { append(": $it") }
                    ex?.cause?.message?.let { append(" ($it)") }
                }
                runIssues += ExecutionIssue(
                    stepMetadata = stepMetadata,
                    kind = ExecutionIssueKind.ORCHESTRATOR_ERROR,
                    message = msg
                )
                return false
            }

            setupEnvironments += environmentId
            return true
        }

        private fun wrapStepCommand(
            step: PlannedStep,
            environmentId: String,
            environmentRef: EnvironmentRef,
            runIssues: MutableList<ExecutionIssue>
        ): PlannedStep? {
            val process = step.process
            if (process !is CommandSpec) return step

            val wrappedSpec = runCatching {
                val baseCommand = buildBaseCommand(process, step.bindings)
                val wrapped = orchestrator.generateExecutionCommand(environmentRef, baseCommand)
                toShellCommandSpec(wrapped)
            }.getOrNull()

            if (wrappedSpec == null) {
                runIssues += ExecutionIssue(
                    stepMetadata = step.metadata,
                    kind = ExecutionIssueKind.ORCHESTRATOR_ERROR,
                    message = "Failed to generate execution command for environment '$environmentId'."
                )
                return null
            }

            return step.copy(process = wrappedSpec)
        }

        private fun buildBaseCommand( spec: CommandSpec, bindings: ResolvedBindings ): String
        {
            val resolver = ArgResolver( bindings )

            val args = spec.args
                .map { resolver.resolve( it ) }
                .joinToString( " " ) { shellQuote( it ) }

            return if ( args.isBlank() ) spec.executable else "${spec.executable} $args"
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
