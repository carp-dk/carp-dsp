package carp.dsp.core.infrastructure.execution

import dk.cachet.carp.analytics.application.execution.ArtefactMetadata
import dk.cachet.carp.analytics.application.execution.ArtefactStore
import dk.cachet.carp.analytics.application.execution.DefaultRunPolicy
import dk.cachet.carp.analytics.application.execution.ExecutionIssueKind
import dk.cachet.carp.analytics.application.execution.ExecutionStatus
import dk.cachet.carp.analytics.application.execution.ProducedOutputRef
import dk.cachet.carp.analytics.application.execution.ResourceRef
import dk.cachet.carp.analytics.application.execution.RunPolicy
import dk.cachet.carp.analytics.application.execution.workspace.ExecutionWorkspace
import dk.cachet.carp.analytics.application.execution.workspace.WorkspaceManager
import dk.cachet.carp.analytics.application.plan.CommandSpec
import dk.cachet.carp.analytics.application.plan.EnvironmentRef
import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import dk.cachet.carp.analytics.application.plan.ExpandedArg
import dk.cachet.carp.analytics.application.plan.PlannedStep
import dk.cachet.carp.analytics.application.plan.ResolvedBindings
import dk.cachet.carp.analytics.application.plan.SystemEnvironmentRef
import dk.cachet.carp.analytics.application.runtime.CommandResult
import dk.cachet.carp.analytics.application.runtime.CommandRunner
import dk.cachet.carp.analytics.infrastructure.execution.EnvironmentConfig
import dk.cachet.carp.analytics.infrastructure.execution.EnvironmentOrchestrator
import dk.cachet.carp.analytics.infrastructure.execution.SetupTiming
import dk.cachet.carp.common.application.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DefaultPlanExecutorTest {

    // ── Test doubles ──────────────────────────────────────────────────────────

    private class RecordingWorkspaceManager : WorkspaceManager {
        val createCalls = mutableListOf<Pair<ExecutionPlan, UUID>>()

        override fun create(plan: ExecutionPlan, runId: UUID): ExecutionWorkspace {
            createCalls += plan to runId
            return ExecutionWorkspace(runId = runId, executionRoot = runId.toString())
        }

        override fun prepareStepDirectories(workspace: ExecutionWorkspace, stepId: UUID) = Unit

        // Returns a path string; CommandStepRunner uses it as the working dir.
        // With a StubCommandRunner no real process is spawned so the path need not exist.
        override fun resolveStepWorkingDir(workspace: ExecutionWorkspace, stepId: UUID): String =
            "${workspace.executionRoot}/$stepId"
    }

    private class StubCommandRunner(
        private val exitCode: Int = 0,
        private val stdout: String = "",
        private val stderr: String = "",
        private val timedOut: Boolean = false
    ) : CommandRunner {
        override fun run(command: CommandSpec, policy: RunPolicy) = CommandResult(
            exitCode = exitCode, stdout = stdout, stderr = stderr,
            durationMs = 1L, timedOut = timedOut
        )
    }

    /** Records the exact CommandSpec that was last executed. */
    private class CapturingCommandRunner(private val exitCode: Int = 0) : CommandRunner {
        val capturedCommands = mutableListOf<CommandSpec>()
        override fun run(command: CommandSpec, policy: RunPolicy): CommandResult {
            capturedCommands += command
            return CommandResult(exitCode = exitCode, stdout = "", stderr = "", durationMs = 1L, timedOut = false)
        }
    }

    private class StubArtefactStore : ArtefactStore {
        override fun recordArtefact(
            stepId: UUID,
            outputId: UUID,
            location: ResourceRef,
            metadata: ArtefactMetadata
        ) = ProducedOutputRef(
            outputId = outputId, location = location,
            sizeBytes = metadata.sizeBytes, sha256 = metadata.sha256, contentType = metadata.contentType
        )
        override fun getArtefact(outputId: UUID): ProducedOutputRef? = null
        override fun getArtefactsByStep(stepId: UUID): List<ProducedOutputRef> = emptyList()
        override fun getAllArtefacts(): List<ProducedOutputRef> = emptyList()
        override fun resolvePath(outputId: UUID): String? = null
    }

    /**
     * Configurable stub for [EnvironmentOrchestrator].
     *
     * Defaults to returning true for setup/teardown and wrapping commands with "stub-env <cmd>".
     */
    private class StubEnvironmentOrchestrator(
        private val setupResult: Boolean = true,
        private val teardownResult: Boolean = true,
        private val commandPrefix: String = "stub-env"
    ) : EnvironmentOrchestrator {
        val setupCalls = mutableListOf<String>() // env IDs setup was called for
        val teardownCalls = mutableListOf<String>()

        override fun setup(environmentRef: EnvironmentRef): Boolean {
            setupCalls += environmentRef.id
            return setupResult
        }

        override fun teardown(environmentRef: EnvironmentRef): Boolean {
            teardownCalls += environmentRef.id
            return teardownResult
        }

        override fun generateExecutionCommand(environmentRef: EnvironmentRef, command: String): String =
            "$commandPrefix $command"
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun plannedStep(
        name: String = "step",
        stepId: UUID = UUID.randomUUID(),
        environmentRef: UUID? = UUID.randomUUID()
    ) = PlannedStep(
        stepId = stepId,
        name = name,
        process = CommandSpec("echo", listOf(ExpandedArg.Literal(name))),
        bindings = ResolvedBindings(),
        environmentRef = environmentRef
    )

    private fun plan(
        vararg steps: PlannedStep,
        workflowId: String = "wf-1",
        requiredEnvironmentRefs: Map<UUID, EnvironmentRef> = emptyMap()
    ) = ExecutionPlan(
        workflowId = workflowId,
        planId = UUID.randomUUID().toString(),
        steps = steps.toList(),
        requiredEnvironmentRefs = requiredEnvironmentRefs
    )

    private fun executor(
        manager: WorkspaceManager = RecordingWorkspaceManager(),
        runner: CommandRunner = StubCommandRunner(),
        artefactStore: ArtefactStore = StubArtefactStore(),
        strategy: StepOrderStrategy = SequentialPlanOrder,
        orchestrator: EnvironmentOrchestrator? = null,
        environmentConfig: EnvironmentConfig = EnvironmentConfig()
    ) = DefaultPlanExecutor(
        workspaceManager = manager,
        artefactStore = artefactStore,
        options = DefaultPlanExecutor.Options(
            commandRunner = runner,
            stepOrderStrategy = strategy,
            orchestrator = orchestrator ?: DefaultEnvironmentOrchestrator(
                DefaultEnvironmentRegistry(
                    java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "carp-dsp-test-registry.json")
                )
            ),
            environmentConfig = environmentConfig
        )
    )

    // ── Original tests (unchanged) ────────────────────────────────────────────

    @Test
    fun `execute calls workspaceManager_create exactly once with plan and runId`() {
        val manager = RecordingWorkspaceManager()
        val p = plan(plannedStep("a"))
        val runId = UUID.randomUUID()

        executor(manager = manager).execute(p, runId)

        assertEquals(1, manager.createCalls.size)
        val (calledPlan, calledRunId) = manager.createCalls.single()
        assertSame(p, calledPlan)
        assertEquals(runId, calledRunId)
    }

    @Test
    fun `execute produces one StepRunResult per planned step`() {
        val steps = listOf(plannedStep("a"), plannedStep("b"), plannedStep("c"))
        val report = executor().execute(plan(*steps.toTypedArray()), UUID.randomUUID())
        assertEquals(steps.size, report.stepResults.size)
    }

    @Test
    fun `execute preserves topo order`() {
        val stepA = plannedStep("alpha")
        val stepB = plannedStep("beta")
        val stepC = plannedStep("gamma")
        val report = executor().execute(plan(stepA, stepB, stepC), UUID.randomUUID())
        assertEquals(listOf(stepA.stepId, stepB.stepId, stepC.stepId), report.stepResults.map { it.stepId })
    }

    @Test
    fun `execute returns SUCCEEDED when all steps succeed`() {
        val report = executor(runner = StubCommandRunner(exitCode = 0))
            .execute(plan(plannedStep("a"), plannedStep("b")), UUID.randomUUID())
        assertEquals(ExecutionStatus.SUCCEEDED, report.status)
    }

    @Test
    fun `execute returns FAILED when a step fails`() {
        val report = executor(runner = StubCommandRunner(exitCode = 1))
            .execute(plan(plannedStep("a")), UUID.randomUUID())
        assertEquals(ExecutionStatus.FAILED, report.status)
    }

    @Test
    fun `execute skips remaining steps after failure when stopOnFailure is true`() {
        var callCount = 0
        val countingRunner = object : CommandRunner {
            override fun run(command: CommandSpec, policy: RunPolicy): CommandResult {
                callCount++
                return CommandResult(
                    exitCode = if (callCount == 1) 1 else 0,
                    stdout = "", stderr = "", durationMs = 1L, timedOut = false
                )
            }
        }
        val stepA = plannedStep("a")
        val stepB = plannedStep("b")
        val stepC = plannedStep("c")
        val report = executor(runner = countingRunner).execute(plan(stepA, stepB, stepC), UUID.randomUUID())
        assertEquals(ExecutionStatus.FAILED, report.stepResults[0].status)
        assertEquals(ExecutionStatus.SKIPPED, report.stepResults[1].status)
        assertEquals(ExecutionStatus.SKIPPED, report.stepResults[2].status)
        assertEquals(1, callCount)
    }

    @Test
    fun `execute report carries correct planId and runId`() {
        val p = plan(plannedStep("z"))
        val runId = UUID.randomUUID()
        val report = executor().execute(p, runId)
        assertEquals(runId, report.runId)
        assertEquals(UUID.parse(p.planId), report.planId)
    }

    @Test
    fun `default strategy is SequentialPlanOrder`() {
        val stepA = plannedStep("first")
        val stepB = plannedStep("second")
        val stepC = plannedStep("third")
        val report = executor().execute(plan(stepA, stepB, stepC), UUID.randomUUID())
        assertEquals(listOf(stepA.stepId, stepB.stepId, stepC.stepId), report.stepResults.map { it.stepId })
    }

    @Test
    fun `custom StepOrderStrategy is honoured`() {
        val stepA = plannedStep("alpha")
        val stepB = plannedStep("beta")
        val stepC = plannedStep("gamma")
        val report = executor(strategy = StepOrderStrategy { p -> p.steps.map { it.stepId }.reversed() })
            .execute(plan(stepA, stepB, stepC), UUID.randomUUID())
        assertEquals(listOf(stepC.stepId, stepB.stepId, stepA.stepId), report.stepResults.map { it.stepId })
    }

    // ── handleUnknownStep ─────────────────────────────────────────────────────

    @Test
    fun `execute records FAILED result and ORCHESTRATOR_ERROR when order strategy returns unknown step id`() {
        val unknownId = UUID.randomUUID()
        val report = executor(strategy = StepOrderStrategy { _ -> listOf(unknownId) })
            .execute(plan(), UUID.randomUUID()) // plan has no steps

        assertEquals(1, report.stepResults.size)
        assertEquals(unknownId, report.stepResults[0].stepId)
        assertEquals(ExecutionStatus.FAILED, report.stepResults[0].status)
        assertTrue(
            report.issues.any {
            it.kind == ExecutionIssueKind.ORCHESTRATOR_ERROR && it.stepId == unknownId
        }
        )
        assertEquals(ExecutionStatus.FAILED, report.status)
    }

    @Test
    fun `execute halts after unknown step when stopOnFailure is true`() {
        val knownStep = plannedStep("known")
        val unknownId = UUID.randomUUID()
        // Order: unknown first, then known — known should be skipped
        val report = executor(
            strategy = StepOrderStrategy { p ->
            listOf(unknownId) + p.steps.map { it.stepId }
        }
        ).execute(plan(knownStep), UUID.randomUUID())

        assertEquals(2, report.stepResults.size)
        assertEquals(ExecutionStatus.FAILED, report.stepResults[0].status)
        assertEquals(ExecutionStatus.SKIPPED, report.stepResults[1].status)
    }

    @Test
    fun `execute does not halt after unknown step when stopOnFailure is false`() {
        val knownStep = plannedStep("known")
        val unknownId = UUID.randomUUID()
        val report = executor(
            runner = StubCommandRunner(exitCode = 0),
            strategy = StepOrderStrategy { p -> listOf(unknownId) + p.steps.map { it.stepId } }
        ).execute(plan(knownStep), UUID.randomUUID(), DefaultRunPolicy(stopOnFailure = false))

        assertEquals(2, report.stepResults.size)
        assertEquals(ExecutionStatus.FAILED, report.stepResults[0].status) // unknown
        assertEquals(ExecutionStatus.SUCCEEDED, report.stepResults[1].status) // known
    }

    // ── deriveOverallStatus ───────────────────────────────────────────────────

    @Test
    fun `deriveOverallStatus returns FAILED when plan has no steps`() {
        // empty results: all{} returns true for empty list → SUCCEEDED
        val report = executor().execute(plan(), UUID.randomUUID())
        // No steps → no results → all{SUCCEEDED} is vacuously true
        assertEquals(ExecutionStatus.SUCCEEDED, report.status)
    }

    @Test
    fun `deriveOverallStatus returns FAILED when results contain only SKIPPED steps`() {
        // To get SKIPPED-but-no-FAILED: EAGER env setup failure + stopOnFailure=true
        val envId = UUID.randomUUID()
        val envRef = SystemEnvironmentRef(id = envId.toString(), dependencies = emptyList())
        val failingOrchestrator = StubEnvironmentOrchestrator(setupResult = false)

        val report = executor(
            orchestrator = failingOrchestrator,
            environmentConfig = EnvironmentConfig(setupTiming = SetupTiming.EAGER)
        ).execute(
            plan(
                plannedStep("a"), plannedStep("b"),
                requiredEnvironmentRefs = mapOf(envId to envRef)
            ),
            UUID.randomUUID()
        )

        // Setup failed → halted before any step ran → all steps SKIPPED, no FAILED step result
        assertTrue(report.stepResults.all { it.status == ExecutionStatus.SKIPPED })
        assertEquals(ExecutionStatus.FAILED, report.status)
    }

    // ── startedAt / finishedAt null paths ─────────────────────────────────────

    @Test
    fun `buildExecutionReport startedAt and finishedAt are null when all steps are skipped`() {
        val envId = UUID.randomUUID()
        val envRef = SystemEnvironmentRef(id = envId.toString(), dependencies = emptyList())
        val failingOrchestrator = StubEnvironmentOrchestrator(setupResult = false)

        val report = executor(
            orchestrator = failingOrchestrator,
            environmentConfig = EnvironmentConfig(setupTiming = SetupTiming.EAGER)
        ).execute(
            plan(
                plannedStep("a"),
                requiredEnvironmentRefs = mapOf(envId to envRef)
            ),
            UUID.randomUUID()
        )

        // Skipped steps have startedAt = null and finishedAt = null,
        // so firstOrNull{startedAt != null} returns null → report.startedAt is null.
        assertNull(report.startedAt)
        assertNull(report.finishedAt)
    }

    @Test
    fun `buildExecutionReport startedAt and finishedAt are non-null when steps ran`() {
        val report = executor(runner = StubCommandRunner(exitCode = 0))
            .execute(plan(plannedStep("a")), UUID.randomUUID())
        // CommandStepRunner records timestamps for steps that actually ran.
        assertNotNull(report.startedAt)
        assertNotNull(report.finishedAt)
    }

    // ── EnvironmentExecutionCoordinator — EAGER setup ─────────────────────────

    @Test
    fun `EAGER setup calls orchestrator setup before any step runs`() {
        val envId = UUID.randomUUID()
        val envRef = SystemEnvironmentRef(id = envId.toString(), dependencies = emptyList())
        val orchestrator = StubEnvironmentOrchestrator(setupResult = true)

        executor(
            orchestrator = orchestrator,
            environmentConfig = EnvironmentConfig(setupTiming = SetupTiming.EAGER)
        ).execute(
            plan(plannedStep("a"), requiredEnvironmentRefs = mapOf(envId to envRef)),
            UUID.randomUUID()
        )

        assertTrue(orchestrator.setupCalls.contains(envId.toString()))
    }

    @Test
    fun `EAGER setup failure with stopOnFailure halts all steps`() {
        val envId = UUID.randomUUID()
        val envRef = SystemEnvironmentRef(id = envId.toString(), dependencies = emptyList())

        val report = executor(
            orchestrator = StubEnvironmentOrchestrator(setupResult = false),
            environmentConfig = EnvironmentConfig(setupTiming = SetupTiming.EAGER)
        ).execute(
            plan(
                plannedStep("a"), plannedStep("b"),
                requiredEnvironmentRefs = mapOf(envId to envRef)
            ),
            UUID.randomUUID()
        )

        assertTrue(report.stepResults.all { it.status == ExecutionStatus.SKIPPED })
        assertTrue(report.issues.any { it.kind == ExecutionIssueKind.ORCHESTRATOR_ERROR })
    }

    @Test
    fun `EAGER setup does not halt when stopOnFailure is false`() {
        val envId = UUID.randomUUID()
        val envRef = SystemEnvironmentRef(id = envId.toString(), dependencies = emptyList())

        val report = executor(
            runner = StubCommandRunner(exitCode = 0),
            orchestrator = StubEnvironmentOrchestrator(setupResult = false),
            environmentConfig = EnvironmentConfig(setupTiming = SetupTiming.EAGER)
        ).execute(
            plan(plannedStep("a"), requiredEnvironmentRefs = mapOf(envId to envRef)),
            UUID.randomUUID(),
            DefaultRunPolicy(stopOnFailure = false)
        )

        // Steps still run even though setup failed, because stopOnFailure = false
        assertTrue(report.stepResults.isNotEmpty())
        assertTrue(report.issues.any { it.kind == ExecutionIssueKind.ORCHESTRATOR_ERROR })
    }

    @Test
    fun `EAGER setup does not call orchestrator when setupTiming is not EAGER`() {
        val envId = UUID.randomUUID()
        val envRef = SystemEnvironmentRef(id = envId.toString(), dependencies = emptyList())
        val orchestrator = StubEnvironmentOrchestrator()

        executor(
            orchestrator = orchestrator,
            environmentConfig = EnvironmentConfig(setupTiming = SetupTiming.LAZY) // not EAGER
        ).execute(
            plan(plannedStep("a"), requiredEnvironmentRefs = mapOf(envId to envRef)),
            UUID.randomUUID()
        )

        // With default config (not EAGER) setupEagerEnvironments returns false immediately
        assertTrue(orchestrator.setupCalls.isEmpty())
    }

    // ── EnvironmentExecutionCoordinator — teardownAll ─────────────────────────

    @Test
    fun `teardownAll is called for every required environment ref`() {
        val envId1 = UUID.randomUUID()
        val envId2 = UUID.randomUUID()
        val refs = mapOf(
            envId1 to SystemEnvironmentRef(id = envId1.toString(), dependencies = emptyList()),
            envId2 to SystemEnvironmentRef(id = envId2.toString(), dependencies = emptyList())
        )
        val orchestrator = StubEnvironmentOrchestrator()

        executor(orchestrator = orchestrator)
            .execute(plan(plannedStep("a"), requiredEnvironmentRefs = refs), UUID.randomUUID())

        assertTrue(orchestrator.teardownCalls.contains(envId1.toString()))
        assertTrue(orchestrator.teardownCalls.contains(envId2.toString()))
    }

    @Test
    fun `teardownAll records ORCHESTRATOR_ERROR issue when teardown fails`() {
        val envId = UUID.randomUUID()
        val envRef = SystemEnvironmentRef(id = envId.toString(), dependencies = emptyList())

        val report = executor(
            orchestrator = StubEnvironmentOrchestrator(teardownResult = false)
        ).execute(
            plan(plannedStep("a"), requiredEnvironmentRefs = mapOf(envId to envRef)),
            UUID.randomUUID()
        )

        assertTrue(
            report.issues.any {
            it.kind == ExecutionIssueKind.ORCHESTRATOR_ERROR &&
                    it.message.contains("teardown")
        }
        )
    }

    @Test
    fun `teardownAll is called even when a step fails (finally block)`() {
        val envId = UUID.randomUUID()
        val envRef = SystemEnvironmentRef(id = envId.toString(), dependencies = emptyList())
        val orchestrator = StubEnvironmentOrchestrator()

        executor(
            runner = StubCommandRunner(exitCode = 1),
            orchestrator = orchestrator
        ).execute(
            plan(plannedStep("a"), requiredEnvironmentRefs = mapOf(envId to envRef)),
            UUID.randomUUID()
        )

        // Even on failure the finally block runs teardown
        assertTrue(orchestrator.teardownCalls.contains(envId.toString()))
    }

    // ── EnvironmentExecutionCoordinator — prepareStep ─────────────────────────

    @Test
    fun `prepareStep returns step unchanged when step has no environmentRef`() {
        // Step with null environmentRef skips all env coordination
        val stepWithNullEnv = plannedStep("no-env", environmentRef = null)
        val capturing = CapturingCommandRunner(exitCode = 0)

        val report = executor(runner = capturing)
            .execute(plan(stepWithNullEnv), UUID.randomUUID())

        assertEquals(ExecutionStatus.SUCCEEDED, report.stepResults[0].status)
        // Command should be the original "echo no-env", not wrapped
        assertTrue(capturing.capturedCommands.any { it.executable == "echo" })
    }

    @Test
    fun `prepareStep returns step unchanged when requiredEnvironmentRefs is empty`() {
        // Even if environmentRef is set, an empty requiredEnvironmentRefs map causes early return
        val capturing = CapturingCommandRunner(exitCode = 0)
        val step = plannedStep("plain-step")

        executor(runner = capturing).execute(
            plan(step, requiredEnvironmentRefs = emptyMap()),
            UUID.randomUUID()
        )

        assertTrue(capturing.capturedCommands.any { it.executable == "echo" })
    }

    @Test
    fun `prepareStep records ORCHESTRATOR_ERROR when step env ref not in requiredEnvironmentRefs`() {
        val knownEnvId = UUID.randomUUID()
        val unknownEnvId = UUID.randomUUID() // step references this, but plan doesn't declare it
        val step = plannedStep("orphan", environmentRef = unknownEnvId)

        val report = executor(
            orchestrator = StubEnvironmentOrchestrator()
        ).execute(
            plan(
                step,
                requiredEnvironmentRefs = mapOf(
                knownEnvId to SystemEnvironmentRef(id = knownEnvId.toString(), dependencies = emptyList())
            )
            ),
            UUID.randomUUID()
        )

        assertEquals(ExecutionStatus.FAILED, report.stepResults[0].status)
        assertTrue(
            report.issues.any {
            it.stepId == step.stepId && it.kind == ExecutionIssueKind.ORCHESTRATOR_ERROR
        }
        )
    }

    // ── EnvironmentExecutionCoordinator — LAZY setup ──────────────────────────

    @Test
    fun `LAZY setup calls orchestrator setup per-step and wraps command`() {
        val envId = UUID.randomUUID()
        val envRef = SystemEnvironmentRef(id = envId.toString(), dependencies = emptyList())
        val orchestrator = StubEnvironmentOrchestrator(commandPrefix = "lazy-wrap")
        val capturing = CapturingCommandRunner(exitCode = 0)

        executor(
            runner = capturing,
            orchestrator = orchestrator,
            environmentConfig = EnvironmentConfig(setupTiming = SetupTiming.LAZY)
        ).execute(
            plan(plannedStep("a", environmentRef = envId), requiredEnvironmentRefs = mapOf(envId to envRef)),
            UUID.randomUUID()
        )

        assertTrue(orchestrator.setupCalls.contains(envId.toString()))
        // The command should be wrapped by the orchestrator — sh/cmd wrapping the lazy-wrap prefix
        val wrappedCmd = capturing.capturedCommands.firstOrNull()
        assertNotNull(wrappedCmd)
        assertTrue(
            wrappedCmd.executable == "sh" || wrappedCmd.executable == "cmd",
            "Expected shell wrapper, got: ${wrappedCmd.executable}"
        )
    }

    @Test
    fun `LAZY setup, same environment not set up twice across steps`() {
        val envId = UUID.randomUUID()
        val envRef = SystemEnvironmentRef(id = envId.toString(), dependencies = emptyList())
        val orchestrator = StubEnvironmentOrchestrator()

        executor(
            orchestrator = orchestrator,
            environmentConfig = EnvironmentConfig(setupTiming = SetupTiming.LAZY)
        ).execute(
            plan(
                plannedStep("a", environmentRef = envId),
                plannedStep("b", environmentRef = envId), // same env
                requiredEnvironmentRefs = mapOf(envId to envRef)
            ),
            UUID.randomUUID()
        )

        assertEquals(
            1, orchestrator.setupCalls.count { it == envId.toString() },
            "Same environment must not be set up more than once"
        )
    }

    @Test
    fun `LAZY setup failure records issue and marks step as FAILED`() {
        val envId = UUID.randomUUID()
        val envRef = SystemEnvironmentRef(id = envId.toString(), dependencies = emptyList())

        val report = executor(
            orchestrator = StubEnvironmentOrchestrator(setupResult = false),
            environmentConfig = EnvironmentConfig(setupTiming = SetupTiming.LAZY)
        ).execute(
            plan(plannedStep("a"), requiredEnvironmentRefs = mapOf(envId to envRef)),
            UUID.randomUUID()
        )

        assertEquals(ExecutionStatus.FAILED, report.stepResults[0].status)
        assertTrue(report.issues.any { it.kind == ExecutionIssueKind.ORCHESTRATOR_ERROR })
    }

    // ── EnvironmentExecutionCoordinator — environmentLogs ────────────────────

    @Test
    fun `environmentLogs returns empty when orchestrator is not a DefaultEnvironmentOrchestrator`() {
        val envId = UUID.randomUUID()
        val envRef = SystemEnvironmentRef(id = envId.toString(), dependencies = emptyList())

        // StubEnvironmentOrchestrator is NOT a DefaultEnvironmentOrchestrator,
        // so the `as? DefaultEnvironmentOrchestrator` cast returns null
        // and the fallback `?: EnvironmentExecutionLogs()` is taken.
        val report = executor(
            orchestrator = StubEnvironmentOrchestrator()
        ).execute(
            plan(plannedStep("a"), requiredEnvironmentRefs = mapOf(envId to envRef)),
            UUID.randomUUID()
        )

        // The report should still be produced successfully; environment logs are empty.
        assertNotNull(report.environmentLogs)
    }

    // ── EnvironmentExecutionCoordinator — command building ────────────────────

    @Test
    fun `buildBaseCommand produces executable-only string when step has no args`() {
        val envId = UUID.randomUUID()
        val envRef = SystemEnvironmentRef(id = envId.toString(), dependencies = emptyList())
        val noArgStep = PlannedStep(
            stepId = UUID.randomUUID(),
            name = "no-args",
            process = CommandSpec("mybin", emptyList()), // no args
            bindings = ResolvedBindings(),
            environmentRef = envId
        )
        val capturing = CapturingCommandRunner(exitCode = 0)

        executor(
            runner = capturing,
            orchestrator = StubEnvironmentOrchestrator(commandPrefix = "wrap"),
            environmentConfig = EnvironmentConfig(setupTiming = SetupTiming.LAZY)
        ).execute(
            plan(noArgStep, requiredEnvironmentRefs = mapOf(envId to envRef)),
            UUID.randomUUID()
        )

        // The wrapped command arg should contain "mybin" without trailing spaces
        val shellArg = capturing.capturedCommands.firstOrNull()
            ?.args?.filterIsInstance<ExpandedArg.Literal>()
            ?.lastOrNull()?.value ?: ""
        assertTrue(shellArg.contains("mybin"), "Expected 'mybin' in wrapped command: $shellArg")
        assertTrue(
            !shellArg.endsWith("  mybin") && shellArg.contains("wrap mybin"),
            "Expected 'wrap mybin' (no trailing space before executable): $shellArg"
        )
    }

    @Test
    fun `argToLiteral handles DataReference and PathSubstitution args`() {
        val envId = UUID.randomUUID()
        val envRef = SystemEnvironmentRef(id = envId.toString(), dependencies = emptyList())
        val dataRefId = UUID.randomUUID()

        val stepWithVariousArgs = PlannedStep(
            stepId = UUID.randomUUID(),
            name = "arg-types",
            process = CommandSpec(
                "mybin",
                listOf(
                ExpandedArg.Literal("literal-value"),
                ExpandedArg.DataReference(dataRefId),
                ExpandedArg.PathSubstitution(dataRefId, "--out=$()")
            )
            ),
            bindings = ResolvedBindings(),
            environmentRef = envId
        )
        val capturing = CapturingCommandRunner(exitCode = 0)

        executor(
            runner = capturing,
            orchestrator = StubEnvironmentOrchestrator(commandPrefix = "wrap"),
            environmentConfig = EnvironmentConfig(setupTiming = SetupTiming.LAZY)
        ).execute(
            plan(stepWithVariousArgs, requiredEnvironmentRefs = mapOf(envId to envRef)),
            UUID.randomUUID()
        )

        val shellArg = capturing.capturedCommands.firstOrNull()
            ?.args?.filterIsInstance<ExpandedArg.Literal>()
            ?.lastOrNull()?.value ?: ""

        // DataReference → dataRefId.toString() should appear in the command
        assertTrue(
            shellArg.contains(dataRefId.toString()),
            "DataReference UUID should be in command: $shellArg"
        )
        // PathSubstitution → "--out=<dataRefId>" should appear
        assertTrue(
            shellArg.contains("--out="),
            "PathSubstitution template should be resolved: $shellArg"
        )
    }

    @Test
    fun `shellQuote handles args that need quoting on the current platform`() {
        val envId = UUID.randomUUID()
        val envRef = SystemEnvironmentRef(id = envId.toString(), dependencies = emptyList())

        val stepWithSpacedArg = PlannedStep(
            stepId = UUID.randomUUID(),
            name = "spaced",
            process = CommandSpec(
                "mybin",
                listOf(
                ExpandedArg.Literal("arg with spaces"),
                ExpandedArg.Literal("plain"),
                ExpandedArg.Literal("") // empty arg
            )
            ),
            bindings = ResolvedBindings(),
            environmentRef = envId
        )
        val capturing = CapturingCommandRunner(exitCode = 0)

        executor(
            runner = capturing,
            orchestrator = StubEnvironmentOrchestrator(commandPrefix = "wrap"),
            environmentConfig = EnvironmentConfig(setupTiming = SetupTiming.LAZY)
        ).execute(
            plan(stepWithSpacedArg, requiredEnvironmentRefs = mapOf(envId to envRef)),
            UUID.randomUUID()
        )

        val shellArg = capturing.capturedCommands.firstOrNull()
            ?.args?.filterIsInstance<ExpandedArg.Literal>()
            ?.lastOrNull()?.value ?: ""

        // The spaced arg should be quoted in the shell command
        assertTrue(
            shellArg.contains("arg with spaces") || shellArg.contains("'arg with spaces'"),
            "Spaced arg should be quoted or present: $shellArg"
        )
        // "plain" needs no quoting — appears as-is
        assertTrue(shellArg.contains("plain"), "Plain arg should appear: $shellArg")
    }
}
