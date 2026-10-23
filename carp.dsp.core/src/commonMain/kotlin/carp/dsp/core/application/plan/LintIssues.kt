package carp.dsp.core.application.plan

import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import dk.cachet.carp.analytics.application.plan.PlanIssue
import dk.cachet.carp.analytics.application.plan.PlanIssueSeverity
import dk.cachet.carp.analytics.domain.validation.ValidationResult
import dk.cachet.carp.analytics.domain.validation.ValidationSeverity

/**
 * Returns this plan with [lint]'s issues ahead of its own.
 *
 * A lint error makes the plan not runnable, so the executor refuses it like any plan
 * error. Each lint issue keeps its code and message, and is attached to the planned
 * step it names when the plan has that step.
 */
fun ExecutionPlan.withLintIssues( lint: ValidationResult ): ExecutionPlan
{
    if ( lint.issues.isEmpty() ) return this

    val stepIdByDescriptorId = steps
        .mapNotNull { step -> step.metadata.descriptorId?.let { it to step.metadata.id } }
        .toMap()
    val lintIssues = lint.issues.map { issue ->
        PlanIssue(
            severity = when ( issue.severity )
            {
                ValidationSeverity.ERROR -> PlanIssueSeverity.ERROR
                ValidationSeverity.WARNING -> PlanIssueSeverity.WARNING
                ValidationSeverity.INFO -> PlanIssueSeverity.INFO
            },
            code = issue.code.name,
            message = issue.message,
            stepId = issue.subjectId?.let { stepIdByDescriptorId[it] }
        )
    }
    return copy( issues = lintIssues + issues )
}
