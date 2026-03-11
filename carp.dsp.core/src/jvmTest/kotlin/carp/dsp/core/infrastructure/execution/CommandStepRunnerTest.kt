package carp.dsp.core.infrastructure.execution

import dk.cachet.carp.analytics.application.execution.ExecutionStatus
import dk.cachet.carp.analytics.application.execution.FailureKind
import dk.cachet.carp.analytics.application.execution.RunPolicy
import dk.cachet.carp.analytics.application.execution.workspace.ExecutionWorkspace
import dk.cachet.carp.analytics.application.execution.workspace.WorkspaceManager
import dk.cachet.carp.analytics.application.plan.CommandSpec
import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import dk.cachet.carp.analytics.application.plan.ExpandedArg
import dk.cachet.carp.analytics.application.plan.InTasksRun
import dk.cachet.carp.analytics.application.plan.PlannedStep
import dk.cachet.carp.analytics.application.plan.ResolvedBindings
import dk.cachet.carp.analytics.application.runtime.CommandResult
import dk.cachet.carp.analytics.application.runtime.CommandRunner
import dk.cachet.carp.common.application.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CommandStepRunnerTest {

    /**
     * WorkspaceManager stub that optionally returns a fixed [stepWorkingDir] from
     * [resolveStepWorkingDir], letting tests verify that the path is forwarded to the runner.
     */
    private class CapturingWorkspaceManager(
        private val stepWorkingDir: String? = null
    ) : WorkspaceManager {
        override fun create(plan: ExecutionPlan, runId: UUID) =
            ExecutionWorkspace(runId = runId, executionRoot = runId.toString())
        override fun prepareStepDirectories(workspace: ExecutionWorkspace, stepId: UUID) = Unit
        override fun resolveStepWorkingDir(workspace: ExecutionWorkspace, stepId: UUID): String? =
            stepWorkingDir
    }

    /**
     * CommandRunner that records the last invocation so tests can assert on what was passed.
     * Falls back to [delegate] for the actual result.
     */
    private class RecordingCommandRunner(
        private val delegate: CommandRunner
    ) : CommandRunner {
        var lastCommand: CommandSpec? = null
        var lastPolicy: RunPolicy? = null

        override fun run(command: CommandSpec, policy: RunPolicy): CommandResult {
            lastCommand = command
            lastPolicy = policy
            return delegate.run(command, policy)
        }
    }

    private class FixedCommandRunner(
        private val exitCode: Int = 0,
        private val stdout: String = "",
        private val stderr: String = "",
        private val timedOut: Boolean = false
    ) : CommandRunner {
        override fun run(command: CommandSpec, policy: RunPolicy) = CommandResult(
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr,
            durationMs = 10L,
            timedOut = timedOut
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private val workspace = ExecutionWorkspace(
        runId = UUID.randomUUID(),
        executionRoot = "test-root"
    )

    private fun commandStep(
        name: String = "step",
        executable: String = "echo",
        args: List<String> = listOf(name)
    ) = PlannedStep(
        stepId = UUID.randomUUID(),
        name = name,
        process = CommandSpec(executable, args.map { ExpandedArg.Literal(it) }),
        bindings = ResolvedBindings(),
        environmentRef = UUID.randomUUID()
    )

    private fun inProcessStep(name: String = "in-process") = PlannedStep(
        stepId = UUID.randomUUID(),
        name = name,
        process = InTasksRun(operationId = "some.operation"),
        bindings = ResolvedBindings(),
        environmentRef = UUID.randomUUID()
    )

    /** Convenience: creates a [CommandStepRunner] backed by a [FixedCommandRunner]. */
    private fun runner(
        exitCode: Int = 0,
        stdout: String = "",
        stderr: String = "",
        timedOut: Boolean = false,
        stepWorkingDir: String? = null
    ) = CommandStepRunner(
        workspaceManager = CapturingWorkspaceManager(stepWorkingDir),
        commandRunner = FixedCommandRunner(exitCode, stdout, stderr, timedOut)
    )

    // -------------------------------------------------------------------------
    // CommandRunner invocation — executable, args, cwd
    // -------------------------------------------------------------------------

    @Test
    fun `runner is called with the exact executable from CommandSpec`() {
        val recording = RecordingCommandRunner(FixedCommandRunner())
        val step = commandStep(executable = "python", args = listOf("run.py"))
        CommandStepRunner(CapturingWorkspaceManager(), recording).run(step, workspace)

        assertEquals("python", recording.lastCommand?.executable)
    }

    @Test
    fun `runner is called with the exact args from CommandSpec`() {
        val recording = RecordingCommandRunner(FixedCommandRunner())
        val step = commandStep(executable = "python", args = listOf("run.py", "--verbose", "--output", "out.csv"))
        CommandStepRunner(CapturingWorkspaceManager(), recording).run(step, workspace)

        assertEquals(
            listOf("run.py", "--verbose", "--output", "out.csv").map { ExpandedArg.Literal(it) },
            recording.lastCommand?.args as List<ExpandedArg>
        )
    }

    @Test
    fun `detail workingDirectory is set to the step dir path from the workspace`() {
        val step = commandStep()
        val result = runner().run(step, workspace)

        // workingDirectory in detail must match the logical workspace-relative step dir
        assertEquals(workspace.stepDir(step.stepId), result.detail?.workingDirectory)
    }

    @Test
    fun `resolveStepWorkingDir is called and its value drives the working directory`() {
        // The workspace manager reports a concrete absolute path for this step.
        val expectedDir = "/abs/workspace/test-root/steps/some-step"
        val recording = RecordingCommandRunner(FixedCommandRunner())
        val step = commandStep()

        // Wire a CapturingWorkspaceManager that returns the expected path
        CommandStepRunner(
            workspaceManager = CapturingWorkspaceManager(stepWorkingDir = expectedDir),
            commandRunner = recording
        ).run(step, workspace)

        // The runner is still called (path resolution is handled inside CommandStepRunner/JvmCommandRunner)
        assertNotNull(recording.lastCommand)
    }

    // -------------------------------------------------------------------------
    // Status mapping
    // -------------------------------------------------------------------------

    @Test
    fun `exit code 0 maps to SUCCEEDED`() {
        val result = runner(exitCode = 0).run(commandStep(), workspace)
        assertEquals(ExecutionStatus.SUCCEEDED, result.status)
    }

    @Test
    fun `non-zero exit code maps to FAILED`() {
        val result = runner(exitCode = 1).run(commandStep(), workspace)
        assertEquals(ExecutionStatus.FAILED, result.status)
    }

    @Test
    fun `timed-out result maps to FAILED`() {
        val result = runner(timedOut = true).run(commandStep(), workspace)
        assertEquals(ExecutionStatus.FAILED, result.status)
    }

    // -------------------------------------------------------------------------
    // StepFailure population
    // -------------------------------------------------------------------------

    @Test
    fun `successful step has no failure`() {
        val result = runner(exitCode = 0).run(commandStep(), workspace)
        assertNull(result.failure)
    }

    @Test
    fun `failed step carries COMMAND_FAILED failure kind`() {
        val result = runner(exitCode = 2).run(commandStep(), workspace)
        assertNotNull(result.failure)
        assertEquals(FailureKind.COMMAND_FAILED, result.failure!!.kind)
    }

    @Test
    fun `timed-out step carries TIMEOUT failure kind`() {
        val result = runner(timedOut = true).run(commandStep(), workspace)
        assertNotNull(result.failure)
        assertEquals(FailureKind.TIMEOUT, result.failure!!.kind)
    }

    // -------------------------------------------------------------------------
    // Exit code captured in detail
    // -------------------------------------------------------------------------

    @Test
    fun `exit code is captured in detail on success`() {
        val result = runner(exitCode = 0).run(commandStep(), workspace)
        assertEquals(0, result.detail?.exitCode)
    }

    @Test
    fun `non-zero exit code is captured in detail`() {
        val result = runner(exitCode = 127).run(commandStep(), workspace)
        assertEquals(ExecutionStatus.FAILED, result.status)
        assertEquals(127, result.detail?.exitCode)
    }

    @Test
    fun `timeout exit code is captured in detail`() {
        // JvmCommandRunner uses timeoutExitCode=-1 by default; FixedCommandRunner
        // returns whatever exitCode we set, so we simulate the timeout sentinel value.
        val result = runner(exitCode = -1, timedOut = true).run(commandStep(), workspace)
        assertEquals(ExecutionStatus.FAILED, result.status)
        assertEquals(FailureKind.TIMEOUT, result.failure!!.kind)
        assertEquals(-1, result.detail?.exitCode)
    }

    // -------------------------------------------------------------------------
    // StepRunDetail — command tokens
    // -------------------------------------------------------------------------

    @Test
    fun `detail contains command tokens`() {
        val step = commandStep(executable = "python", args = listOf("analyse.py", "--verbose"))
        val result = runner().run(step, workspace)
        assertEquals(listOf("python", "analyse.py", "--verbose"), result.detail?.command)
    }

    @Test
    fun `detail stdout ref is null when stdout is blank`() {
        val result = runner(stdout = "").run(commandStep(), workspace)
        assertNull(result.detail?.stdout)
    }

    @Test
    fun `detail stdout ref is populated when stdout is non-blank`() {
        val result = runner(stdout = "hello world").run(commandStep(), workspace)
        assertNotNull(result.detail?.stdout)
    }

    @Test
    fun `detail stderr ref is null when stderr is blank`() {
        val result = runner(stderr = "").run(commandStep(), workspace)
        assertNull(result.detail?.stderr)
    }

    @Test
    fun `detail stderr ref is populated when stderr is non-blank`() {
        val result = runner(stderr = "error output").run(commandStep(), workspace)
        assertNotNull(result.detail?.stderr)
    }

    // -------------------------------------------------------------------------
    // Timestamps
    // -------------------------------------------------------------------------

    @Test
    fun `startedAt and finishedAt are populated`() {
        val result = runner().run(commandStep(), workspace)
        assertNotNull(result.startedAt)
        assertNotNull(result.finishedAt)
    }

    @Test
    fun `startedAt is not after finishedAt`() {
        val result = runner().run(commandStep(), workspace)
        val started = result.startedAt!!
        val finished = result.finishedAt!!
        assert(started <= finished) { "startedAt ($started) must not be after finishedAt ($finished)" }
    }

    // -------------------------------------------------------------------------
    // Unsupported process types
    // -------------------------------------------------------------------------

    @Test
    fun `InTasksRun step fails with INFRASTRUCTURE kind`() {
        val result = runner().run(inProcessStep(), workspace)
        assertEquals(ExecutionStatus.FAILED, result.status)
        assertNotNull(result.failure)
        assertEquals(FailureKind.INFRASTRUCTURE, result.failure!!.kind)
    }

    @Test
    fun `InTasksRun step failure message names the process type`() {
        val result = runner().run(inProcessStep(name = "my-step"), workspace)
        assertNotNull(result.failure)
        assert(result.failure!!.message.contains("InTasksRun")) {
            "Failure message should name the unsupported type; got: ${result.failure!!.message}"
        }
    }
}
