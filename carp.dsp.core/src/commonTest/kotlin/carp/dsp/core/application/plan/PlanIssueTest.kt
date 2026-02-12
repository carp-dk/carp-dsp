package carp.dsp.core.application.plan

import kotlin.test.Test
import kotlin.test.assertFailsWith

class PlanIssueTest {

    @Test
    fun `constructs plan-level issue when stepId is null`() {
        PlanIssue(
            severity = PlanIssueSeverity.WARNING,
            code = "W1",
            message = "warn",
            stepId = null
        )
    }

    @Test
    fun `constructs step-scoped issue when stepId is non-blank`() {
        PlanIssue(
            severity = PlanIssueSeverity.INFO,
            code = "I1",
            message = "info",
            stepId = "s1"
        )
    }

    @Test
    fun `rejects blank code in init`() {
        assertFailsWith<IllegalArgumentException> {
            PlanIssue(
                severity = PlanIssueSeverity.INFO,
                code = " ",
                message = "msg",
                stepId = null
            )
        }
    }

    @Test
    fun `rejects blank message in init`() {
        assertFailsWith<IllegalArgumentException> {
            PlanIssue(
                severity = PlanIssueSeverity.INFO,
                code = "C",
                message = " ",
                stepId = null
            )
        }
    }

    @Test
    fun `rejects blank stepId when provided in init`() {
        assertFailsWith<IllegalArgumentException> {
            PlanIssue(
                severity = PlanIssueSeverity.INFO,
                code = "C",
                message = "msg",
                stepId = " "
            )
        }
    }
}
