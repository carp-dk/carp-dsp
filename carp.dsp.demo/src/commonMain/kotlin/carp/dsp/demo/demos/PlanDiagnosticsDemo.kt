package carp.dsp.demo.demos

import carp.dsp.core.application.plan.DefaultExecutionPlanner
import carp.dsp.core.application.plan.SHA256PlanHasher
import carp.dsp.demo.api.Demo
import carp.dsp.demo.utils.PlanDisplayUtils
import carp.dsp.demo.workflows.DummyWorkflows

/**
 * Demo showcasing P3 diagnostics & hashing capabilities.
 *
 * This demo demonstrates:
 * - Building a WorkflowDefinition with step dependencies
 * - Running plan() to create an ExecutionPlan
 * - Computing deterministic SHA-256 plan hash
 * - Displaying comprehensive diagnostics (issues, validity, bindings, hash)
 *
 * **Note:** Uses real [SHA256PlanHasher] for integration testing.
 * This demo runs in jvmCommon where Java imports are allowed.
 */
object PlanDiagnosticsDemo : Demo {
    override val id = "p3-diagnostics"
    override val title = "P3 Diagnostics & Plan Hashing Demo"

    override fun run() {
        printDemoHeader()

        // Build the workflow definition
        println("Building workflow definition...")
        val definition = DummyWorkflows.p0PlanningDefinition()

        PlanDisplayUtils.printWorkflowDefinitionSummary(definition)

        println("\nRunning planner...")

        // Create planner and generate execution plan
        val planner = DefaultExecutionPlanner()
        val plan = planner.plan(definition)

        println("[OK] Plan created: ${plan.planId}")

        // Compute diagnostics with actual SHA256PlanHasher
        println("\nComputing diagnostics with SHA256 hashing...")
        val hasher = SHA256PlanHasher()  // ✅ Real hasher (jvmCommon integration test)
        val diags = plan.diagnostics(hasher)
        println("[OK] Diagnostics computed")

        // Print diagnostics summary
        printDiagnosticsSummary(plan, diags)

        printDemoFooter()
    }

    private fun printDemoHeader() {
        println("\n" + "=".repeat(70))
        println("  P3 DIAGNOSTICS & HASHING DEMO")
        println("=".repeat(70))
        println("Demonstrating plan diagnostics, SHA-256 hashing, and reproducibility")
        println()
    }

    private fun printDemoFooter() {
        println("\n" + "=".repeat(70))
        println("[OK] P3 Diagnostics Demo completed!")
        println("=".repeat(70))
        println()
    }

    private fun printDiagnosticsSummary(
        plan: dk.cachet.carp.analytics.application.plan.ExecutionPlan,
        diags: dk.cachet.carp.analytics.application.plan.PlanDiagnostics
    ) {
        println("\nEXECUTION PLAN DIAGNOSTICS SUMMARY")
        println("-".repeat(70))
        println()

        // Workflow & Plan IDs
        println("📋 IDENTITY")
        println("   Workflow ID: ${diags.workflowId}")
        println("   Plan ID:     ${diags.planId}")
        println()

        // Statistics
        println("📊 STATISTICS")
        println("   Steps:        ${diags.stepCount}")
        println("   Environments: ${diags.environmentCount}")
        println("   Bindings:     ${diags.bindingCount}")
        println()

        // Execution Order
        println("🔄 EXECUTION ORDER")
        PlanDisplayUtils.printExecutionOrder(plan)
        println()

        // Environment References
        println("🌍 REQUIRED ENVIRONMENTS")
        PlanDisplayUtils.printRequiredEnvironments(plan)
        println()

        // Issues by Severity
        println("⚠️  ISSUES DETECTED")
        if (plan.issues.isEmpty()) {
            println("   [OK] No issues!")
        } else {
            println("   Errors:   ${diags.issueSummary.errorCount}")
            println("   Warnings: ${diags.issueSummary.warningCount}")
            println("   Info:     ${diags.issueSummary.infoCount}")
            println()
            println("   Details (sorted by severity):")

            PlanDisplayUtils.printIssuesBySeverity(plan.issues, indent = "", detailIndent = "      ")
        }
        println()

        // Plan Hash
        println("#️⃣  PLAN HASH (Deterministic Reproducibility)")
        println("   SHA-256: ${diags.planHash}")
        println("   Length:  ${diags.planHash.length} chars (64 = valid SHA-256)")
        println()

        // Overall Status
        println("✅ STATUS")
        println("   Valid:    ${diags.isValid}")
        println("   Runnable: ${plan.isRunnable()}")
        println()
        println("-".repeat(70))
    }
}