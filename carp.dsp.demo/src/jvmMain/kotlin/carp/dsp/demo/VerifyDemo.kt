package carp.dsp.demo

import carp.dsp.demo.workflows.DummyWorkflows
import carp.dsp.core.application.plan.DefaultExecutionPlanner

fun verifyP0Demo() {
    // Test workflow creation
    val definition = DummyWorkflows.p0PlanningDefinition()
    println("Workflow: ${definition.workflow.metadata.name}")

    // Test planner
    val planner = DefaultExecutionPlanner()
    val plan = planner.plan(definition)
    println("Plan created with ${plan.steps.size} steps, runnable: ${plan.isRunnable()}")

    // Test demo registration
    val demo = DemoRegistry.byId("p0-planning")
    println("Demo registered: ${demo?.title ?: "NOT FOUND"}")

    println("✅ P0 Planning Demo verification complete!")
}

fun main() = verifyP0Demo()
