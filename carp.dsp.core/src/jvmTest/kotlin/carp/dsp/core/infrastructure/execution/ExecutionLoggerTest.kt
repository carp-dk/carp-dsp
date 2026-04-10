package carp.dsp.core.infrastructure.execution

import carp.dsp.core.application.execution.ExecutionLogger
import carp.dsp.core.application.execution.NoOpExecutionLogger
import carp.dsp.core.testing.RecordingWorkspaceManager
import carp.dsp.core.testing.StubArtefactStore
import carp.dsp.core.testing.StubCommandRunner
import dk.cachet.carp.analytics.application.execution.DefaultRunPolicy
import dk.cachet.carp.analytics.application.plan.*
import dk.cachet.carp.analytics.domain.workflow.StepMetadata
import dk.cachet.carp.common.application.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ── Test double ───────────────────────────────────────────────────────────────

/**
 * Records every lifecycle event fired by [DefaultPlanExecutor].
 * Lets tests assert the correct sequence and arguments without relying on log output.
 */
class CapturingExecutionLogger : ExecutionLogger {

    sealed class Event {
        data class Started(val runId: UUID, val stepId: UUID, val stepName: String) : Event()
        data class Completed(val runId: UUID, val stepId: UUID, val stepName: String, val durationMs: Long) : Event()
        data class Failed(val runId: UUID, val stepId: UUID, val stepName: String, val reason: String) : Event()
    }

    val events = mutableListOf<Event>()

    override fun onStepStarted(runId: UUID, stepId: UUID, stepName: String) {
        events += Event.Started(runId, stepId, stepName)
    }

    override fun onStepCompleted(runId: UUID, stepId: UUID, stepName: String, durationMs: Long) {
        events += Event.Completed(runId, stepId, stepName, durationMs)
    }

    override fun onStepFailed(runId: UUID, stepId: UUID, stepName: String, reason: String) {
        events += Event.Failed(runId, stepId, stepName, reason)
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun step(name: String, id: UUID = UUID.randomUUID()) = PlannedStep(
    metadata = StepMetadata(id = id, name = name),
    process = CommandSpec("echo", listOf(ExpandedArg.Literal(name))),
    bindings = ResolvedBindings(),
    environmentRef = null
)

private fun plan(vararg steps: PlannedStep) = ExecutionPlan(
    workflowName = "test",
    planId = UUID.randomUUID().toString(),
    steps = steps.toList(),
    requiredEnvironmentRefs = emptyMap()
)

private fun executor(logger: ExecutionLogger) = DefaultPlanExecutor(
    workspaceManager = RecordingWorkspaceManager(),
    artefactStore = StubArtefactStore(),
    options = DefaultPlanExecutor.Options(
        commandRunner = StubCommandRunner(),
        executionLogger = logger
    )
)

// ── Tests ─────────────────────────────────────────────────────────────────────

class ExecutionLoggerTest {

    @Test
    fun `onStepStarted fired before onStepCompleted for each step`() {
        val log = CapturingExecutionLogger()
        executor(log).execute(plan(step("a"), step("b")), UUID.randomUUID())

        val types = log.events.map { it::class.simpleName }
        assertEquals(
            listOf("Started", "Completed", "Started", "Completed"),
            types
        )
    }

    @Test
    fun `events carry correct step names`() {
        val log = CapturingExecutionLogger()
        executor(log).execute(plan(step("alpha"), step("beta")), UUID.randomUUID())

        val names = log.events.filterIsInstance<CapturingExecutionLogger.Event.Started>()
            .map { it.stepName }
        assertEquals(listOf("alpha", "beta"), names)
    }

    @Test
    fun `events carry the run id passed to execute`() {
        val log = CapturingExecutionLogger()
        val runId = UUID.randomUUID()
        executor(log).execute(plan(step("a")), runId)

        assertTrue(log.events.all { event ->
            when (event) {
                is CapturingExecutionLogger.Event.Started -> event.runId == runId
                is CapturingExecutionLogger.Event.Completed -> event.runId == runId
                is CapturingExecutionLogger.Event.Failed -> event.runId == runId
            }
        })
    }

    @Test
    fun `onStepFailed fired when step exits non-zero`() {
        val log = CapturingExecutionLogger()
        executor(log)
        val failingExecutor = DefaultPlanExecutor(
            workspaceManager = RecordingWorkspaceManager(),
            artefactStore = StubArtefactStore(),
            options = DefaultPlanExecutor.Options(
                commandRunner = StubCommandRunner(exitCode = 1),
                executionLogger = log
            )
        )
        failingExecutor.execute(plan(step("fail-me")), UUID.randomUUID())

        assertTrue(log.events.any { it is CapturingExecutionLogger.Event.Failed })
        assertTrue(log.events.none { it is CapturingExecutionLogger.Event.Completed })
    }

    @Test
    fun `completed event includes non-negative duration`() {
        val log = CapturingExecutionLogger()
        executor(log).execute(plan(step("a")), UUID.randomUUID())

        val completed = log.events.filterIsInstance<CapturingExecutionLogger.Event.Completed>()
        assertTrue(completed.isNotEmpty())
        assertTrue(completed.all { it.durationMs >= 0 })
    }

    @Test
    fun `one started and one completed or failed per step`() {
        val log = CapturingExecutionLogger()
        executor(log).execute(plan(step("a"), step("b"), step("c")), UUID.randomUUID())

        val started = log.events.filterIsInstance<CapturingExecutionLogger.Event.Started>()
        val terminal = log.events.filter {
            it is CapturingExecutionLogger.Event.Completed || it is CapturingExecutionLogger.Event.Failed
        }
        assertEquals(3, started.size)
        assertEquals(3, terminal.size)
    }

    @Test
    fun `NoOpExecutionLogger does not throw`() {
        // Sanity check — the default must be safe to use without any setup
        executor(NoOpExecutionLogger).execute(plan(step("a")), UUID.randomUUID())
    }

    @Test
    fun `skipped steps do not fire events`() {
        val log = CapturingExecutionLogger()
        val failingExecutor = DefaultPlanExecutor(
            workspaceManager = RecordingWorkspaceManager(),
            artefactStore = StubArtefactStore(),
            options = DefaultPlanExecutor.Options(
                commandRunner = StubCommandRunner(exitCode = 1),
                executionLogger = log
            )
        )
        // stopOnFailure=true (default) — step b and c should be skipped
        failingExecutor.execute(
            plan(step("a"), step("b"), step("c")),
            UUID.randomUUID(),
            DefaultRunPolicy(stopOnFailure = true)
        )

        // Only step a should have fired events — b and c are skipped silently
        assertEquals(1, log.events.filterIsInstance<CapturingExecutionLogger.Event.Started>().size)
    }
}
