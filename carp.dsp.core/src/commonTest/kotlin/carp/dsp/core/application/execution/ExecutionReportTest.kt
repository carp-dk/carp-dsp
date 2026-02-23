package carp.dsp.core.application.execution

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue


class ExecutionReportTest {

    private val json = Json {
        encodeDefaults = true
    classDiscriminator = "type"
    }

    @Test
    fun `round-trip serialization with mixed step results`() {
        val report = ExecutionReport(
            workflowId = "wf-1",
            planId = "plan-1",
            startedAtEpochMs = 100L,
            durationMs = 250L,
            stepResults = listOf(
                StepSucceeded(
                    stepId = "s1",
                    startedAtEpochMs = 110L,
                    durationMs = 10L,
                    detail = InProcessDetail(operationId = "op-123")
                ),
                StepFailed(
                    stepId = "s2",
                    startedAtEpochMs = 130L,
                    durationMs = 20L,
                    failure = StepFailure(
                        kind = FailureKind.NON_ZERO_EXIT,
                        message = "Command returned non-zero exit code.",
                        info = CommandInfo(
                            executable = "python",
                            args = listOf("-c", "exit(2)"),
                            exitCode = 2,
                            stderr = "boom"
                        )
                    ),
                    detail = CommandDetail(
                        executable = "python",
                        args = listOf("-c", "exit(2)"),
                        exitCode = 2,
                        stderr = "boom"
                    )
                ),
                StepSkipped(
                    stepId = "s3",
                    reason = "precondition unmet"
                )
            ),
            notes = listOf("planner warning: minor issue")
        )

        val encoded = json.encodeToString(report)
        val decoded = json.decodeFromString<ExecutionReport>(encoded)

        assertEquals(report, decoded)
        assertTrue(encoded.contains("\"workflowId\":\"wf-1\""))
        assertTrue(encoded.contains("\"type\":\"Failed\"")) // discriminator from StepRunResult
    }


    @Test
    fun `validate rejects blank workflowId`() {
        val report = ExecutionReport(workflowId = " ")
        assertFailsWith<IllegalArgumentException> { report.validate() }
    }

    @Test
    fun `validate rejects blank planId when provided`() {
        val report = ExecutionReport(workflowId = "wf", planId = " ")
        assertFailsWith<IllegalArgumentException> { report.validate() }
    }

    @Test
    fun `validate rejects negative timestamps`() {
        val report = ExecutionReport(workflowId = "wf", startedAtEpochMs = -1L)
        assertFailsWith<IllegalArgumentException> { report.validate() }
    }

    @Test
    fun `validate rejects blank notes`() {
        val report = ExecutionReport(workflowId = "wf", notes = listOf("ok", " "))
        assertFailsWith<IllegalArgumentException> { report.validate() }
    }

    @Test
    fun `validate calls step validation`() {
        val report = ExecutionReport(
            workflowId = "wf",
            stepResults = listOf(
                StepSucceeded(stepId = " ") // invalid
            )
        )
        assertFailsWith<IllegalArgumentException> { report.validate() }
    }

    @Test
    fun `validate allows duplicate stepIds for retries`() {
        val report = ExecutionReport(
            workflowId = "wf",
            stepResults = listOf(
                StepFailed(
                    stepId = "s1",
                    failure = StepFailure(kind = FailureKind.NON_ZERO_EXIT, message = "first attempt failed")
                ),
                StepSucceeded(stepId = "s1") // retry succeeded
            )
        )

        // Should not throw
        report.validate()
    }

    @Test
    fun `isSuccessLatest uses the latest attempt per stepId`() {
        val report = ExecutionReport(
            workflowId = "wf",
            stepResults = listOf(
                StepFailed(
                    stepId = "s1",
                    failure = StepFailure(kind = FailureKind.NON_ZERO_EXIT, message = "attempt 1 failed")
                ),
                StepSucceeded(stepId = "s1"),
                StepFailed(
                    stepId = "s2",
                    failure = StepFailure(kind = FailureKind.TIMEOUT, message = "attempt 1 failed")
                )
            )
        )

        // s1 latest = success, s2 latest = failed => overall false
        assertFalse(report.isSuccessLatest())
        assertEquals(1, report.failedLatestResults().size)
        assertEquals("s2", report.failedLatestResults().single().stepId)
    }

    @Test
    fun `latestResultFor returns last occurrence`() {
        val report = ExecutionReport(
            workflowId = "wf",
            stepResults = listOf(
                StepFailed(stepId = "s1", failure = StepFailure(kind = FailureKind.TIMEOUT, message = "a")),
                StepFailed(stepId = "s1", failure = StepFailure(kind = FailureKind.TIMEOUT, message = "b"))
            )
        )

        val latest = report.latestResultFor("s1") as StepFailed
        assertEquals("b", latest.failure.message)
    }

    @Test
    fun `resultsFor returns all attempts for a stepId in order`() {
        val a1: StepRunResult =
            StepFailed(stepId = "A", failure = StepFailure(FailureKind.NON_ZERO_EXIT, "fail-1"))
        val b1: StepRunResult =
            StepSucceeded(stepId = "B")
        val a2: StepRunResult =
            StepSucceeded(stepId = "A")

        val report = ExecutionReport(
            workflowId = "wf",
            stepResults = listOf(a1, b1, a2)
        )

        val results = report.resultsFor("A")
        assertEquals(listOf(a1, a2), results)
        assertEquals(2, results.size)
    }

    @Test
    fun `resultsFor returns empty list when stepId not present`() {
        val report = ExecutionReport(
            workflowId = "wf",
            stepResults = listOf(StepSucceeded(stepId = "A"))
        )

        assertTrue(report.resultsFor("missing").isEmpty())
    }

    @Test
    fun `latestResultFor returns null when stepId not present`() {
        val report = ExecutionReport(
            workflowId = "wf",
            stepResults = listOf(StepSucceeded(stepId = "A"))
        )

        assertNull(report.latestResultFor("missing"))
    }

    @Test
    fun `latestResultFor returns last attempt when multiple exist`() {
        val a1: StepRunResult =
            StepFailed(stepId = "A", failure = StepFailure(FailureKind.TIMEOUT, "attempt-1"))
        val a2: StepRunResult =
            StepFailed(stepId = "A", failure = StepFailure(FailureKind.NON_ZERO_EXIT, "attempt-2"))
        val a3: StepRunResult =
            StepSucceeded(stepId = "A")

        val report = ExecutionReport(
            workflowId = "wf",
            stepResults = listOf(a1, a2, a3)
        )

        assertEquals(a3, report.latestResultFor("A"))
    }

    @Test
    fun `failedResults returns only failed attempts across all steps`() {
        val a1 = StepFailed(stepId = "A", failure = StepFailure(FailureKind.NON_ZERO_EXIT, "a"))
        val b1 = StepSucceeded(stepId = "B")
        val c1 = StepFailed(stepId = "C", failure = StepFailure(FailureKind.TIMEOUT, "c"))

        val report = ExecutionReport(
            workflowId = "wf",
            stepResults = listOf(a1, b1, c1)
        )

        assertEquals(listOf(a1, c1), report.failedResults())
    }

    @Test
    fun `failedLatestResults returns failed results among latest attempts only`() {
        // A fails then succeeds => latest is success (not in failedLatestResults)
        val a1 = StepFailed(stepId = "A", failure = StepFailure(FailureKind.NON_ZERO_EXIT, "a1"))
        val a2 = StepSucceeded(stepId = "A")

        // B succeeds then fails => latest is failure (included)
        // this is unlikely to happen in practice, but an edge case that ensures correct behavior
        val b1 = StepSucceeded(stepId = "B")
        val b2 = StepFailed(stepId = "B", failure = StepFailure(FailureKind.TIMEOUT, "b2"))

        val report = ExecutionReport(
            workflowId = "wf",
            stepResults = listOf(a1, a2, b1, b2)
        )

        val latestFailed = report.failedLatestResults()
        assertEquals(1, latestFailed.size)
        assertEquals("B", latestFailed.single().stepId)

        // Sanity: overall success latest should be false (B latest failed)
        assertFalse(report.isSuccessLatest())
    }

    @Test
    fun `succeededLatestResults returns succeeded results among latest attempts only`() {
        // A fails then succeeds => latest is success (included)
        val a1 = StepFailed(stepId = "A", failure = StepFailure(FailureKind.NON_ZERO_EXIT, "a1"))
        val a2 = StepSucceeded(stepId = "A")

        // B succeeds then fails => latest is failure (not included)
        val b1 = StepSucceeded(stepId = "B")
        val b2 = StepFailed(stepId = "B", failure = StepFailure(FailureKind.TIMEOUT, "b2"))

        // C skipped => latest is skipped (not included)
        val c1 = StepSkipped(stepId = "C", reason = "skip")

        val report = ExecutionReport(
            workflowId = "wf",
            stepResults = listOf(a1, a2, b1, b2, c1)
        )

        val latestSucceeded = report.succeededLatestResults()
        assertEquals(1, latestSucceeded.size)
        assertEquals("A", latestSucceeded.single().stepId)
    }

    @Test
    fun `skippedLatestResults returns skipped results among latest attempts only`() {
        // A skipped then succeeded => latest is success (not included)
        val a1 = StepSkipped(stepId = "A", reason = "skip-1")
        val a2 = StepSucceeded(stepId = "A")

        // B skipped => latest is skipped (included)
        val b1 = StepSkipped(stepId = "B", reason = "skip-b")

        val report = ExecutionReport(
            workflowId = "wf",
            stepResults = listOf(a1, a2, b1)
        )

        val latestSkipped = report.skippedLatestResults()
        assertEquals(1, latestSkipped.size)
        assertEquals("B", latestSkipped.single().stepId)

        // Sanity: overall success latest should be true (no latest failures)
        assertTrue(report.isSuccessLatest())
    }

    @Test
    fun `validate accepts null planId and null timestamps`() {
        val report = ExecutionReport(
            workflowId = "wf",
            planId = null,
            startedAtEpochMs = null,
            durationMs = null,
            stepResults = emptyList(),
            notes = emptyList(),
        )

        // Should not throw (covers null branches)
        report.validate()
    }

    @Test
    fun `validate rejects negative duration`() {
        val report = ExecutionReport(workflowId = "wf", durationMs = -1L)
        assertFailsWith<IllegalArgumentException> { report.validate() }
    }

    @Test
    fun `isSuccessLatest is true for empty report`() {
        val report = ExecutionReport(workflowId = "wf")
        assertTrue(report.isSuccessLatest())
        assertTrue(report.latestResultsByStepId().isEmpty())
    }

    @Test
    fun `helpers resultsFor returns all attempts for matching stepId`() {
        val a1: StepRunResult =
            StepFailed(stepId = "A", failure = StepFailure(kind = FailureKind.NON_ZERO_EXIT, message = "a1"))
        val b1: StepRunResult =
            StepSucceeded(stepId = "B")
        val a2: StepRunResult =
            StepSucceeded(stepId = "A")

        val report = ExecutionReport(
            workflowId = "wf",
            stepResults = listOf(a1, b1, a2)
        )

        assertEquals(listOf(a1, a2), report.resultsFor("A"))
        assertTrue(report.resultsFor("missing").isEmpty())
    }

    @Test
    fun `helpers latestResultFor returns null when absent and last occurrence when present`() {
        val a1 = StepFailed(stepId = "A", failure = StepFailure(kind = FailureKind.TIMEOUT, message = "a1"))
        val a2 = StepSucceeded(stepId = "A")

        val report = ExecutionReport(
            workflowId = "wf",
            stepResults = listOf(a1, a2)
        )

        assertNull(report.latestResultFor("missing"))
        assertEquals(a2, report.latestResultFor("A"))
    }

    @Test
    fun `helpers failedResults returns all failed attempts`() {
        val a1 = StepFailed(stepId = "A", failure = StepFailure(kind = FailureKind.NON_ZERO_EXIT, message = "a1"))
        val b1 = StepSucceeded(stepId = "B")
        val c1 = StepFailed(stepId = "C", failure = StepFailure(kind = FailureKind.TIMEOUT, message = "c1"))

        val report = ExecutionReport(
            workflowId = "wf",
            stepResults = listOf(a1, b1, c1)
        )

        assertEquals(listOf(a1, c1), report.failedResults())
    }

    @Test
    fun `helpers latestResultsByStepId chooses last attempt per step`() {
        val a1 = StepFailed(stepId = "A", failure = StepFailure(kind = FailureKind.NON_ZERO_EXIT, message = "a1"))
        val a2 = StepSucceeded(stepId = "A")
        val b1 = StepSkipped(stepId = "B", reason = "skip")

        val report = ExecutionReport(
            workflowId = "wf",
            stepResults = listOf(a1, a2, b1)
        )

        val latest = report.latestResultsByStepId()
        assertEquals(2, latest.size)
        assertEquals(a2, latest["A"])
        assertEquals(b1, latest["B"])
    }

    @Test
    fun `helpers failedLatestResults includes only steps whose latest attempt failed`() {
        // A fails then succeeds => latest success => excluded
        val a1 = StepFailed(stepId = "A", failure = StepFailure(kind = FailureKind.NON_ZERO_EXIT, message = "a1"))
        val a2 = StepSucceeded(stepId = "A")

        // B succeeds then fails => latest fail => included
        val b1 = StepSucceeded(stepId = "B")
        val b2 = StepFailed(stepId = "B", failure = StepFailure(kind = FailureKind.TIMEOUT, message = "b2"))

        val report = ExecutionReport(
            workflowId = "wf",
            stepResults = listOf(a1, a2, b1, b2)
        )

        val latestFailed = report.failedLatestResults()
        assertEquals(1, latestFailed.size)
        assertEquals("B", latestFailed.single().stepId)
        assertFalse(report.isSuccessLatest())
    }

    @Test
    fun `helpers succeededLatestResults and skippedLatestResults reflect latest attempt only`() {
        // A skipped then succeeded => latest success
        val a1 = StepSkipped(stepId = "A", reason = "skip-a1")
        val a2 = StepSucceeded(stepId = "A")

        // B failed then skipped => latest skipped
        val b1 = StepFailed(stepId = "B", failure = StepFailure(kind = FailureKind.INTERNAL_ERROR, message = "b1"))
        val b2 = StepSkipped(stepId = "B", reason = "skip-b2")

        // C succeeded then failed => latest failed
        val c1 = StepSucceeded(stepId = "C")
        val c2 = StepFailed(stepId = "C", failure = StepFailure(kind = FailureKind.NON_ZERO_EXIT, message = "c2"))

        val report = ExecutionReport(
            workflowId = "wf",
            stepResults = listOf(a1, a2, b1, b2, c1, c2)
        )

        val latestSucceeded = report.succeededLatestResults()
        val latestSkipped = report.skippedLatestResults()
        val latestFailed = report.failedLatestResults()

        assertEquals(listOf("A"), latestSucceeded.map { it.stepId })
        assertEquals(listOf("B"), latestSkipped.map { it.stepId })
        assertEquals(listOf("C"), latestFailed.map { it.stepId })
        assertFalse(report.isSuccessLatest())
    }
}
