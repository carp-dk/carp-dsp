package carp.dsp.core.application.plan

import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import dk.cachet.carp.analytics.application.plan.PlanIssue
import dk.cachet.carp.analytics.application.plan.PlanIssueSeverity
import dk.cachet.carp.analytics.domain.validation.ValidationErrorCode
import dk.cachet.carp.analytics.domain.validation.ValidationIssue
import dk.cachet.carp.analytics.domain.validation.ValidationResult
import dk.cachet.carp.analytics.domain.validation.ValidationSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class LintIssuesTest
{
    private val plannerIssue = PlanIssue( PlanIssueSeverity.WARNING, "PLANNER_WARNING", "from the planner" )
    private val plan = ExecutionPlan(
        workflowName = "wf",
        planId = "plan-1",
        steps = emptyList(),
        issues = listOf( plannerIssue )
    )

    @Test
    fun `no lint issues leaves the plan unchanged`()
    {
        assertSame( plan, plan.withLintIssues( ValidationResult.OK ) )
    }

    @Test
    fun `a lint error comes first and makes the plan not runnable`()
    {
        val lint = ValidationResult(
            listOf(
                ValidationIssue(
                    severity = ValidationSeverity.ERROR,
                    code = ValidationErrorCode.WORKFLOW_MISSING_ENVIRONMENT,
                    message = "Step 'gsd' references non-existent environment 'env-x'.",
                    subjectId = "gsd"
                )
            )
        )

        val linted = plan.withLintIssues( lint )

        assertEquals( listOf( "WORKFLOW_MISSING_ENVIRONMENT", "PLANNER_WARNING" ), linted.issues.map { it.code } )
        assertEquals( PlanIssueSeverity.ERROR, linted.issues.first().severity )
        assertNull( linted.issues.first().stepId ) // no planned step 'gsd' in this plan
        assertFalse( linted.isRunnable() )
    }

    @Test
    fun `a lint warning keeps its severity`()
    {
        val lint = ValidationResult(
            listOf( ValidationIssue( ValidationSeverity.WARNING, ValidationErrorCode.UNUSED_ENVIRONMENT, "unused" ) )
        )

        assertEquals( PlanIssueSeverity.WARNING, plan.withLintIssues( lint ).issues.first().severity )
    }
}
