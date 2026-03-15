package carp.dsp.core.infrastructure.execution

import dk.cachet.carp.analytics.application.execution.ResourceKind
import dk.cachet.carp.analytics.application.execution.StepLogRecorder
import dk.cachet.carp.analytics.application.execution.workspace.ExecutionWorkspace
import dk.cachet.carp.analytics.application.plan.InTasksRun
import dk.cachet.carp.analytics.application.plan.PlannedStep
import dk.cachet.carp.analytics.application.plan.ResolvedBindings
import dk.cachet.carp.analytics.application.runtime.CommandResult
import dk.cachet.carp.common.application.UUID
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileSystemStepLogRecorderTest {

    private lateinit var tmpDir: Path
    private lateinit var recorder: StepLogRecorder
    private lateinit var workspace: ExecutionWorkspace

    @BeforeTest
    fun setup() {
        tmpDir = Files.createTempDirectory("step-log-recorder-test")
        recorder = FileSystemStepLogRecorder()
        workspace = ExecutionWorkspace(
            runId = UUID.randomUUID(),
            executionRoot = tmpDir.toString()
        )
    }

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun cleanup() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun recordsStdoutAndStderr() {
        val step = createTestStep()
        val result = CommandResult(
            exitCode = 0,
            stdout = "Output line 1\nOutput line 2",
            stderr = "Error line 1\nError line 2",
            timedOut = false,
            durationMs = 1000L
        )

        val ref = recorder.recordLogs(step, result, workspace)

        assertNotNull(ref)
        assertEquals(ResourceKind.RELATIVE_PATH, ref.kind)
        assertTrue(ref.value.startsWith("logs/"))
        assertTrue(ref.value.endsWith(".log"))
    }

    @Test
    fun recordsStdoutOnly() {
        val step = createTestStep()
        val result = CommandResult(
            exitCode = 0,
            stdout = "Output only",
            stderr = "",
            timedOut = false,
            durationMs = 1000L
        )

        val ref = recorder.recordLogs(step, result, workspace)

        assertNotNull(ref)
        val logPath = tmpDir.resolve(ref.value)
        assertTrue(logPath.toFile().exists())
        val content = logPath.readText()
        assertTrue(content.contains("=== STDOUT ==="))
        assertTrue(content.contains("Output only"))
    }

    @Test
    fun recordsStderrOnly() {
        val step = createTestStep()
        val result = CommandResult(
            exitCode = 1,
            stdout = "",
            stderr = "Error message",
            timedOut = false,
            durationMs = 1000L
        )

        val ref = recorder.recordLogs(step, result, workspace)

        assertNotNull(ref)
        val logPath = tmpDir.resolve(ref.value)
        val content = logPath.readText()
        assertTrue(content.contains("=== STDERR ==="))
        assertTrue(content.contains("Error message"))
    }

    @Test
    fun skipsEmptyLogs() {
        val step = createTestStep()
        val result = CommandResult(
            exitCode = 0,
            stdout = "",
            stderr = "",
            timedOut = false,
            durationMs = 1000L
        )

        val ref = recorder.recordLogs(step, result, workspace)

        assertNull(ref) // No logs = no recording
    }

    @Test
    fun createsLogsDirectory() {
        val step = createTestStep()
        val result = CommandResult(
            exitCode = 0,
            stdout = "test output",
            stderr = "",
            timedOut = false,
            durationMs = 1000L
        )

        recorder.recordLogs(step, result, workspace)

        val logsDir = tmpDir.resolve("logs")
        assertTrue(logsDir.toFile().exists())
        assertTrue(logsDir.toFile().isDirectory)
    }

    @Test
    fun formatsLogFileCorrectly() {
        val step = createTestStep()
        val result = CommandResult(
            exitCode = 0,
            stdout = "stdout content",
            stderr = "stderr content",
            timedOut = false,
            durationMs = 1000L
        )

        val ref = recorder.recordLogs(step, result, workspace)
        val logPath = tmpDir.resolve(ref?.value ?: "")
        val content = logPath.readText()

        // Check order: STDOUT first, then STDERR
        val stdoutIndex = content.indexOf("=== STDOUT ===")
        val stderrIndex = content.indexOf("=== STDERR ===")
        assertTrue(stdoutIndex < stderrIndex)
    }

    @Test
    fun handlesWhitespaceCorrectly() {
        val step = createTestStep()
        val result = CommandResult(
            exitCode = 0,
            stdout = "  leading spaces\ntrailing spaces  ",
            stderr = "  error with spaces  ",
            timedOut = false,
            durationMs = 1000L
        )

        val ref = recorder.recordLogs(step, result, workspace)

        assertNotNull(ref)
        val logPath = tmpDir.resolve(ref.value)
        val content = logPath.readText()
        assertTrue(content.contains("  leading spaces"))
        assertTrue(content.contains("trailing spaces  "))
    }

    // Helpers

    private fun createTestStep(): PlannedStep =
        PlannedStep(
            stepId = UUID.randomUUID(),
            name = "test-step",
            process = InTasksRun(operationId = "test.operation"),
            bindings = ResolvedBindings(),
            environmentRef = UUID.randomUUID()
        )
}
