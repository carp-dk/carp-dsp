package carp.dsp.core.infrastructure.execution

import dk.cachet.carp.analytics.application.execution.ExecutionStatus
import dk.cachet.carp.analytics.application.execution.RunPolicy
import dk.cachet.carp.analytics.application.execution.workspace.ExecutionWorkspace
import dk.cachet.carp.analytics.application.execution.workspace.WorkspaceManager
import dk.cachet.carp.analytics.application.plan.CommandSpec
import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import dk.cachet.carp.analytics.application.plan.PlannedStep
import dk.cachet.carp.analytics.application.plan.ResolvedBindings
import dk.cachet.carp.analytics.application.runtime.CommandResult
import dk.cachet.carp.analytics.application.runtime.CommandRunner
import dk.cachet.carp.common.application.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class DefaultPlanExecutorTest {

    // -------------------------------------------------------------------------
    // Test doubles
    // -------------------------------------------------------------------------

    /** Records every call to [create] and returns a stub workspace. */
    private class RecordingWorkspaceManager : WorkspaceManager {
        val createCalls = mutableListOf<Pair<ExecutionPlan, UUID>>()

        override fun create(plan: ExecutionPlan, runId: UUID): ExecutionWorkspace {
            createCalls += plan to runId
            return ExecutionWorkspace(runId = runId, executionRoot = runId.toString())
        }

        override fun prepareStepDirectories(workspace: ExecutionWorkspace, stepId: UUID) = Unit
    }

    /**
     * Stub runner that returns a fixed [CommandResult] without spawning any OS process.
     * Defaults to exit code 0 (success).
     */
    private class StubCommandRunner(
        private val exitCode: Int = 0,
        private val stdout: String = "",
        private val stderr: String = "",
        private val timedOut: Boolean = false
    ) : CommandRunner {
        override fun run(command: CommandSpec, policy: RunPolicy) = CommandResult(
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr,
            durationMs = 1L,
            timedOut = timedOut
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun plannedStep(name: String = "step") = PlannedStep(
        stepId = UUID.randomUUID(),
        name = name,
        process = CommandSpec("echo", listOf(name)),
        bindings = ResolvedBindings(),
        environmentDefinitionId = UUID.randomUUID()
    )

    private fun plan(vararg steps: PlannedStep) = ExecutionPlan(
        workflowId = "wf-1",
        planId = UUID.randomUUID().toString(),
        steps = steps.toList()
    )

    private fun executor(
        manager: WorkspaceManager = RecordingWorkspaceManager(),
        runner: CommandRunner = StubCommandRunner(),
        strategy: StepOrderStrategy = SequentialPlanOrder
    ) = DefaultPlanExecutor(
        workspaceManager = manager,
        commandRunner = runner,
        stepOrderStrategy = strategy
    )

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun `execute calls workspaceManager_create exactly once with plan and runId`() {
        val manager = RecordingWorkspaceManager()
        val p = plan(plannedStep("a"))
        val runId = UUID.randomUUID()

        executor(manager = manager).execute(p, runId)

        assertEquals(1, manager.createCalls.size, "workspaceManager.create must be called exactly once")
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
    fun `execute preserves topo order - results appear in plan declaration order`() {
        val stepA = plannedStep("alpha")
        val stepB = plannedStep("beta")
        val stepC = plannedStep("gamma")

        val report = executor().execute(plan(stepA, stepB, stepC), UUID.randomUUID())

        assertEquals(
            listOf(stepA.stepId, stepB.stepId, stepC.stepId),
            report.stepResults.map { it.stepId }
        )
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
        // First step fails, second and third should be SKIPPED
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

        val report = executor(runner = countingRunner)
            .execute(plan(stepA, stepB, stepC), UUID.randomUUID())

        assertEquals(ExecutionStatus.FAILED, report.stepResults[0].status)
        assertEquals(ExecutionStatus.SKIPPED, report.stepResults[1].status)
        assertEquals(ExecutionStatus.SKIPPED, report.stepResults[2].status)
        assertEquals(1, callCount, "Runner must not be invoked for skipped steps")
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
    fun `default strategy is SequentialPlanOrder - follows planner order`() {
        val stepA = plannedStep("first")
        val stepB = plannedStep("second")
        val stepC = plannedStep("third")

        val report = executor().execute(plan(stepA, stepB, stepC), UUID.randomUUID())

        assertEquals(
            listOf(stepA.stepId, stepB.stepId, stepC.stepId),
            report.stepResults.map { it.stepId }
        )
    }

    @Test
    fun `custom StepOrderStrategy is honoured - reversed order example`() {
        val stepA = plannedStep("alpha")
        val stepB = plannedStep("beta")
        val stepC = plannedStep("gamma")

        val report = executor(strategy = StepOrderStrategy { p -> p.steps.map { it.stepId }.reversed() })
            .execute(plan(stepA, stepB, stepC), UUID.randomUUID())

        assertEquals(
            listOf(stepC.stepId, stepB.stepId, stepA.stepId),
            report.stepResults.map { it.stepId }
        )
    }
}
