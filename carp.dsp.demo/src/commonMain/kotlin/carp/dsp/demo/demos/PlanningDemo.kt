package carp.dsp.demo.demos

import carp.dsp.core.application.plan.DefaultExecutionPlanner
import carp.dsp.demo.api.Demo
import carp.dsp.demo.workflows.DummyWorkflows
import dk.cachet.carp.analytics.application.plan.PlanIssueSeverity

/**
 * Demo showcasing P0 planning capabilities with DefaultExecutionPlanner.
 *
 * This demo demonstrates:
 * - Building a WorkflowDefinition with step dependencies
 * - Running DefaultExecutionPlanner.plan() to create an ExecutionPlan
 * - Displaying plan summary with deterministic ordering
 * - Showing issue detection and runnable status
 */
object PlanningDemo : Demo {
    override val id = "p0-planning"
    override val title = "P0 Planning Demo"

    override fun run() {
        printDemoHeader()

        // Build the workflow definition
        println("📋 Building workflow definition...")
        val definition = DummyWorkflows.p0PlanningDefinition()

        println("✅ Created workflow: ${definition.workflow.metadata.name}")
        println("   - ${definition.workflow.getComponents().size} steps")
        println("   - ${definition.environments.size} environment(s)")

        println("\n🔧 Running DefaultExecutionPlanner...")

        // Create planner and generate execution plan
        val planner = DefaultExecutionPlanner()
        val plan = planner.plan(definition)

        // Print plan summary
        printPlanSummary(plan)

        printDemoFooter()
    }

    private fun printDemoHeader() {
        println("\n" + "=".repeat(60))
        println("🚀 P0 PLANNING DEMO")
        println("=".repeat(60))
        println("Demonstrating workflow planning with step dependencies")
        println()
    }

    private fun printDemoFooter() {
        println("\n" + "=".repeat(60))
        println("✅ P0 Planning Demo completed!")
        println("=".repeat(60))
    }

    private fun printPlanSummary(plan: dk.cachet.carp.analytics.application.plan.ExecutionPlan) {
        println("\n📊 EXECUTION PLAN SUMMARY")
        println("-".repeat(40))

        // Basic plan info
        println("Workflow ID: ${plan.workflowId}")
        println("Plan ID:     ${plan.planId}")
        println("Runnable:    ${plan.isRunnable()}")
        println("Has Errors:  ${plan.hasErrors()}")

        // Planned steps (in execution order)
        println("\n📋 PLANNED STEPS (execution order):")
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
        println("\n🏗️ REQUIRED ENVIRONMENTS:")
        if (plan.requiredEnvironmentHandles.isEmpty()) {
            println("   (no environments required)")
        } else {
            plan.requiredEnvironmentHandles.sortedBy { it.toString() }.forEach { envId ->
                println("   - $envId")
            }
        }

        // Issues grouped by severity (sorted for determinism)
        println("\n⚠️ PLANNING ISSUES:")
        if (plan.issues.isEmpty()) {
            println("   ✅ No issues detected!")
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
                        println("   ${severityIcon(severity)} $severity (${issues.size}):")
                        issues.forEach { issue ->
                            val stepInfo = if (issue.stepId != null) " [${issue.stepId}]" else ""
                            println("      - ${issue.code}$stepInfo: ${issue.message}")
                        }
                    }
                }
        }
    }

    private fun severityIcon(severity: PlanIssueSeverity): String = when (severity) {
        PlanIssueSeverity.ERROR -> "❌"
        PlanIssueSeverity.WARNING -> "⚠️"
        PlanIssueSeverity.INFO -> "ℹ️"
    }
}
