package carp.dsp.demo.demos

import carp.dsp.core.application.plan.DefaultExecutionPlanner
import carp.dsp.demo.api.Demo
import carp.dsp.demo.workflows.DummyWorkflows
import dk.cachet.carp.analytics.application.execution.workspace.WorkspaceManager
import dk.cachet.carp.common.application.UUID

/**
 * Demo for E0: Plan → Workspace.
 *
 * Exercises the full Plan → Workspace path without executing any steps:
 * - Builds a WorkflowDefinition
 * - Produces an ExecutionPlan via DefaultExecutionPlanner
 * - Creates a deterministic execution workspace
 * - Prints the workspace layout (root + per-step dirs)
 *
 * No StepRunner, no CommandRunner, no environment materialization.
 *
 * @param workspaceManager Concrete WorkspaceManager implementation (provided by JVM entrypoint).
 * @param runId Optional fixed run ID; a random UUID is used when null.
 */
class PlanToWorkspaceDemo(
    private val workspaceManager: WorkspaceManager,
    private val runId: UUID = UUID.randomUUID()
) : Demo {

    override val id = "dry-run"
    override val title = "Dry-Run: Plan + Workspace Layout (E0)"

    override fun run() {
        println()
        println("=".repeat(60))
        println("  DRY-RUN WORKSPACE DEMO  (E0)")
        println("=".repeat(60))
        println("Plan -> Workspace only. No steps executed.")
        println()

        // 1. Build workflow definition
        println("Building workflow definition...")
        val definition = DummyWorkflows.p0PlanningDefinition()
        println("   Workflow : ${definition.workflow.metadata.name}")
        println("   Steps    : ${definition.workflow.getComponents().size}")
        println()

        // 2. Plan
        println("Planning...")
        val planner = DefaultExecutionPlanner()
        val plan = planner.plan(definition)

        if (!plan.isRunnable()) {
            println("[FAIL] Plan is not runnable. Issues:")
            plan.issues.forEach { println("   [${it.severity}] ${it.code}: ${it.message}") }
            println("=".repeat(60))
            return
        }
        println("   [OK] Plan is runnable  (${plan.steps.size} steps, 0 errors)")
        println()

        // 3. Create workspace
        println("Creating workspace...")
        val workspace = workspaceManager.create(plan, runId)
        println()

        // 4. Print layout
        println("RUN ID        : $runId")
        println("EXECUTION ROOT: ${workspace.executionRoot}")
        println()
        println("PER-STEP LAYOUT (sorted by stepId):")
        println("-".repeat(60))

        plan.steps.sortedBy { it.stepId.toString() }.forEach { step ->
            println("  Step ${step.stepId}")
            println("    work dir : ${workspace.stepDir(step.stepId)}")
            println("    inputs/  : ${workspace.stepInputsDir(step.stepId)}")
            println("    outputs/ : ${workspace.stepOutputsDir(step.stepId)}")
            println("    logs/    : ${workspace.stepLogsDir(step.stepId)}")
        }

        println("-".repeat(60))
        println()
        println("[OK] Dry-run complete. No steps were executed.")
        println("=".repeat(60))
    }
}