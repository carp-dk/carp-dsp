package carp.dsp.demo

import carp.dsp.demo.demos.PlanningDemo
import carp.dsp.demo.workflows.DummyWorkflows

/**
 * Simple test to verify P0 Planning Demo works correctly.
 * This is not a unit test - just a verification that the components work.
 */
fun testPlanningDemo() {
    println("🧪 Testing P0 Planning Demo Components")
    println("=" .repeat(50))

    try {
        // Test workflow creation
        println("1. Testing workflow creation...")
        val definition = DummyWorkflows.p0PlanningDefinition()
        println("   ✅ Workflow created: ${definition.workflow.metadata.name}")
        println("   ✅ Steps: ${definition.workflow.getComponents().size}")
        println("   ✅ Environments: ${definition.environments.size}")

        // Test demo registration
        println("\n2. Testing demo registration...")
        val demo = DemoRegistry.byId("p0-planning")
        if (demo != null) {
            println("   ✅ Demo found in registry: ${demo.title}")
        } else {
            println("   ❌ Demo NOT found in registry!")
            return
        }

        // Test demo execution (capture output)
        println("\n3. Testing demo execution...")
        println("   Running P0PlanningDemo.run()...")
        println("   " + "-".repeat(40))

        PlanningDemo.run()

        println("   " + "-".repeat(40))
        println("   ✅ Demo executed successfully!")

        println("\n🎉 All P0 Planning Demo components working correctly!")

    } catch (e: Exception) {
        println("❌ Error testing P0 Planning Demo: ${e.message}")
        e.printStackTrace()
    }
}

fun main() {
    testPlanningDemo()
}
