package carp.dsp.core.application.execution

import kotlin.test.Test
import kotlin.test.assertFailsWith

class StepRunResultValidationTest {

    @Test fun `validate rejects blank stepId`() {
        val r: StepRunResult = StepSucceeded(stepId = " ")
        assertFailsWith<IllegalArgumentException> { r.validate() }
    }

    @Test fun `validate rejects negative startedAt`() {
        val r: StepRunResult = StepSucceeded(stepId = "s", startedAtEpochMs = -1L)
        assertFailsWith<IllegalArgumentException> { r.validate() }
    }

    @Test fun `validate rejects negative duration`() {
        val r: StepRunResult = StepSucceeded(stepId = "s", durationMs = -5L)
        assertFailsWith<IllegalArgumentException> { r.validate() }
    }

    @Test fun `skipped rejects blank reason`() {
        assertFailsWith<IllegalArgumentException> { StepSkipped(stepId = "s", reason = " ") }
    }

    @Test fun `failure rejects blank message`() {
        assertFailsWith<IllegalArgumentException> {
            StepFailure(kind = FailureKind.INTERNAL_ERROR, message = " ").validate()
        }
    }

    @Test fun `CommandInfo rejects blank executable`() {
        assertFailsWith<IllegalArgumentException> { CommandInfo(executable = " ") }
    }

    @Test fun `CommandInfo rejects negative exitCode`() {
        assertFailsWith<IllegalArgumentException> {
            CommandInfo(executable = "x", exitCode = -1)
        }
    }

    @Test fun `InProcessInfo rejects blank operationId`() {
        assertFailsWith<IllegalArgumentException> { InProcessInfo(operationId = " ") }
    }

    @Test fun `CommandDetail rejects blank executable`() {
        assertFailsWith<IllegalArgumentException> { CommandDetail(executable = " ", exitCode = 0) }
    }

    @Test fun `CommandDetail rejects negative exitCode`() {
        assertFailsWith<IllegalArgumentException> { CommandDetail(executable = "x", exitCode = -1) }
    }

    @Test fun `InProcessDetail rejects blank operationId`() {
        assertFailsWith<IllegalArgumentException> { InProcessDetail(operationId = " ") }
    }
}
