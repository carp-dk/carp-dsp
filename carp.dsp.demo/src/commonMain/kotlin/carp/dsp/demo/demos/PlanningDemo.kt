package carp.dsp.demo.demos

import carp.dsp.core.application.plan.DefaultExecutionPlanner
import carp.dsp.demo.api.Demo
import carp.dsp.demo.workflows.DummyWorkflows
import dk.cachet.carp.analytics.application.plan.CondaEnvironmentRef
import dk.cachet.carp.analytics.application.plan.PixiEnvironmentRef
import dk.cachet.carp.analytics.application.plan.PlanIssueSeverity
import dk.cachet.carp.analytics.application.plan.SystemEnvironmentRef

/**
 * Demo showcasing P0 planning capabilities with requiredEnvironmentRefs.
 *
 * This demo demonstrates:
 * - Building a WorkflowDefinition with step dependencies
 * - Running requiredEnvironmentRefs.plan() to create an ExecutionPlan
 * - Displaying plan summary with deterministic ordering
 * - Showing issue detection and runnable status
 */
object PlanningDemo : Demo {
    override val id = "p0-planning"
    override val title = "P0 Planning Demo"

    override fun run() {
        printDemoHeader()

        // Build the workflow definition
        println("Building workflow definition...")
        val definition = DummyWorkflows.p0PlanningDefinition()

        println("[OK] Created workflow: ${definition.workflow.metadata.name}")
        println("   - ${definition.workflow.getComponents().size} steps")
        println("   - ${definition.environments.size} environment(s)")

        println("\nRunning requiredEnvironmentRefs...")

        // Create planner and generate execution plan
        val planner = DefaultExecutionPlanner()
        val plan = planner.plan(definition)

        // Print plan summary
        printPlanSummary(plan)

        printDemoFooter()
    }

    private fun printDemoHeader() {
        println("\n" + "=".repeat(60))
        println("  P0 PLANNING DEMO")
        println("=".repeat(60))
        println("Demonstrating workflow planning with step dependencies")
        println()
    }

    private fun printDemoFooter() {
        println("\n" + "=".repeat(60))
        println("[OK] P0 Planning Demo completed!")
        println("=".repeat(60))
    }

    private fun printPlanSummary(plan: dk.cachet.carp.analytics.application.plan.ExecutionPlan) {
        println("\nEXECUTION PLAN SUMMARY")
        println("-".repeat(40))

        // Basic plan info
        println("Workflow ID: ${plan.workflowId}")
        println("Plan ID:     ${plan.planId}")
        println("Runnable:    ${plan.isRunnable()}")
        println("Has Errors:  ${plan.hasErrors()}")

        // Planned steps (in execution order)
        println("\nPLANNED STEPS (execution order):")
        if (plan.steps.isEmpty()) {
            println("   (no steps planned)")
        } else {
            plan.steps.forEachIndexed { index, step ->
                val processType = when {
                    step.process.toString().contains("CommandRun") -> "Command"
                    step.process.toString().contains("InTasksRun") -> "Internal"
                    else -> "Unknown"
                }
                println("   ${index + 1}. ${step.stepId} :: ${step.name} :: $processType")
            }
        }

        // Required environments (sorted for determinism)
        println("\nREQUIRED ENVIRONMENTS:")
        if (plan.requiredEnvironmentRefs.isEmpty()) {
            println("   (no environments required)")
        } else {
            plan.requiredEnvironmentRefs.entries.sortedBy { it.key.toString() }.forEach { (envId, envRef) ->
                val envType = when (envRef) {
                    is CondaEnvironmentRef -> "conda"
                    is PixiEnvironmentRef -> "pixi"
                    is SystemEnvironmentRef -> "system"
                }
                println("   - $envId ($envType)")
            }
        }

        // Issues grouped by severity (sorted for determinism)
        println("\nPLANNING ISSUES:")
        if (plan.issues.isEmpty()) {
            println("   [OK] No issues detected!")
        } else {
            val issuesBySeverity = plan.issues
                .groupBy { it.severity }
                .mapValues { (_, issues) ->
                    issues.sortedWith(compareBy({ it.code }, { it.stepId?.toString() }))
                }

            // Display in severity order: ERROR, WARNING, INFO
            listOf(PlanIssueSeverity.ERROR, PlanIssueSeverity.WARNING, PlanIssueSeverity.INFO)
                .forEach { severity ->
                    val issues = issuesBySeverity[severity]
                    if (!issues.isNullOrEmpty()) {
                        println("   ${severityLabel(severity)} $severity (${issues.size}):")
                        issues.forEach { issue ->
                            val stepInfo = if (issue.stepId != null) " [${issue.stepId}]" else ""
                            println("      - ${issue.code}$stepInfo: ${issue.message}")
                        }
                    }
                }
        }
    }

    private fun severityLabel(severity: PlanIssueSeverity): String = when (severity) {
        PlanIssueSeverity.ERROR -> "[FAIL]"
        PlanIssueSeverity.WARNING -> "[WARN]"
        PlanIssueSeverity.INFO -> "[INFO]"
    }
}
