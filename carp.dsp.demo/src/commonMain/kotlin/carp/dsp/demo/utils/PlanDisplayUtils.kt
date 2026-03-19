package carp.dsp.demo.utils

import dk.cachet.carp.analytics.application.plan.CondaEnvironmentRef
import dk.cachet.carp.analytics.application.plan.EnvironmentRef
import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import dk.cachet.carp.analytics.application.plan.PlanIssue
import dk.cachet.carp.analytics.application.plan.PlanIssueSeverity
import dk.cachet.carp.analytics.application.plan.PixiEnvironmentRef
import dk.cachet.carp.analytics.application.plan.REnvironmentRef
import dk.cachet.carp.analytics.application.plan.SystemEnvironmentRef
import dk.cachet.carp.analytics.domain.workflow.WorkflowDefinition

/**
 * Utility functions for displaying execution plan information in demos.
 */
object PlanDisplayUtils {

    /**
     * Prints workflow definition summary information.
     */
    fun printWorkflowDefinitionSummary(definition: WorkflowDefinition) {
        println("[OK] Created workflow: ${definition.workflow.metadata.name}")
        println("   - ${definition.workflow.getComponents().size} steps")
        println("   - ${definition.environments.size} environment(s)")
    }

    /**
     * Gets a human-readable environment type string from an [EnvironmentRef].
     */
    fun getEnvironmentType(envRef: EnvironmentRef): String = when (envRef) {
        is CondaEnvironmentRef -> "conda"
        is PixiEnvironmentRef -> "pixi"
        is REnvironmentRef -> "R"
        is SystemEnvironmentRef -> "system"
    }

    /**
     * Gets a severity label for display purposes.
     */
    fun getSeverityLabel(severity: PlanIssueSeverity): String = when (severity) {
        PlanIssueSeverity.ERROR -> "[FAIL]"
        PlanIssueSeverity.WARNING -> "[WARN]"
        PlanIssueSeverity.INFO -> "[INFO]"
    }

    /**
     * Groups and sorts issues by severity for deterministic display.
     * Returns a map of severity to sorted issues.
     */
    fun groupAndSortIssues(issues: List<PlanIssue>): Map<PlanIssueSeverity, List<PlanIssue>> {
        return issues
            .groupBy { it.severity }
            .mapValues { (_, issueList) ->
                issueList.sortedWith(compareBy({ it.code }, { it.stepId?.toString() }))
            }
    }

    /**
     * Prints execution order of steps in a plan.
     */
    fun printExecutionOrder(plan: ExecutionPlan, indent: String = "   ") {
        if (plan.steps.isEmpty()) {
            println("${indent}(no steps)")
        } else {
            plan.steps.forEachIndexed { idx, step ->
                println("${indent}${idx + 1}. ${step.metadata.id} :: ${step.metadata.name}")
            }
        }
    }

    /**
     * Prints required environments from a plan with deterministic sorting.
     */
    fun printRequiredEnvironments(plan: ExecutionPlan, indent: String = "   ") {
        if (plan.requiredEnvironmentRefs.isEmpty()) {
            println("${indent}(none)")
        } else {
            plan.requiredEnvironmentRefs.entries
                .sortedBy { it.key.toString() }
                .forEach { (envId, envRef) ->
                    val envType = getEnvironmentType(envRef)
                    println("${indent}- $envId ($envType)")
                }
        }
    }

    /**
     * Prints issues grouped by severity with deterministic ordering.
     */
    fun printIssuesBySeverity(
        issues: List<PlanIssue>,
        indent: String = "   ",
        detailIndent: String = "      "
    ) {
        if (issues.isEmpty()) {
            println("${indent}[OK] No issues!")
            return
        }

        val issuesBySeverity = groupAndSortIssues(issues)

        listOf(PlanIssueSeverity.ERROR, PlanIssueSeverity.WARNING, PlanIssueSeverity.INFO)
            .forEach { severity ->
                val severityIssues = issuesBySeverity[severity]
                if (!severityIssues.isNullOrEmpty()) {
                    val label = getSeverityLabel(severity)
                    severityIssues.forEach { issue ->
                        val stepInfo = if (issue.stepId != null) " [${issue.stepId}]" else ""
                        println("$detailIndent$label ${issue.code}$stepInfo: ${issue.message}")
                    }
                }
            }
    }

    /**
     * Prints issues with severity counts and details.
     * Used in PlanningDemo where counts are displayed per severity.
     */
    fun printIssuesWithCounts(
        issues: List<PlanIssue>,
        indent: String = "   ",
        detailIndent: String = "      "
    ) {
        if (issues.isEmpty()) {
            println("${indent}[OK] No issues detected!")
            return
        }

        val issuesBySeverity = groupAndSortIssues(issues)

        listOf(PlanIssueSeverity.ERROR, PlanIssueSeverity.WARNING, PlanIssueSeverity.INFO)
            .forEach { severity ->
                val severityIssues = issuesBySeverity[severity]
                if (!severityIssues.isNullOrEmpty()) {
                    println("${indent}${getSeverityLabel(severity)} $severity (${severityIssues.size}):")
                    severityIssues.forEach { issue ->
                        val stepInfo = if (issue.stepId != null) " [${issue.stepId}]" else ""
                        println("${detailIndent}- ${issue.code}$stepInfo: ${issue.message}")
                    }
                }
            }
    }
}



