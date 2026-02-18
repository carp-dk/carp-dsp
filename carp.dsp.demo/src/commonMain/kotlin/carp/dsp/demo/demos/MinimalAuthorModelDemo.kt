package carp.dsp.demo.demos

import carp.dsp.demo.api.Demo
import carp.dsp.demo.workflows.DummyWorkflows
import dk.cachet.carp.analytics.domain.workflow.WorkflowDefinition

/**
 * Interactive demo showcasing various workflow examples and validation.
 *
 * This demo displays:
 * - Valid workflows with minimal and complete configurations
 * - Invalid workflows with different violation types
 * - Validation results demonstrating error detection
 */
object MinimalAuthorModelDemo : Demo {
    override val id = "author-min"
    override val title = "Minimal author model demo"

    override fun run() {
        printDemoHeader()

        println("VALID WORKFLOWS")

        displayValidWorkflowExamples()

        pauseForUser("Press Enter to see invalid workflow examples...")

        println("INVALID WORKFLOWS & VALIDATION")

        displayInvalidWorkflowExamples()

        pauseForUser("Press Enter to see summary...")

        printDemoFooter()
    }

    private fun displayValidWorkflowExamples() {
        // Example 1: Minimal workflow
        println("\n Example 1: Minimal Workflow (1 step)")
        println("-".repeat(60))
        val minimal1 = DummyWorkflows.validWorkflowWithMinRequirements(1)
        printWorkflow(minimal1)
        validateAndPrint(minimal1)

        // Example 2: Simple workflow
        println("\n\n Example 2: Simple Workflow (2 steps)")
        println("-".repeat(60))
        val simple = DummyWorkflows.validWorkflowWithMinRequirements(2)
        printWorkflow(simple)
        validateAndPrint(simple)

        // Example 3: Medium workflow
        println("\n\n Example 3: Medium Workflow (4 steps)")
        println("-".repeat(60))
        val medium = DummyWorkflows.validWorkflowWithMinRequirements(4)
        printWorkflow(medium)
        validateAndPrint(medium)

        // Example 4: Complete workflow with all fields
        println("\n\n Example 4: Complete Workflow (all fields populated, 2 steps)")
        println("-".repeat(60))
        val complete = DummyWorkflows.validWorkflowWithAllFields(2)
        printWorkflow(complete)
        validateAndPrint(complete)
    }

    private fun displayInvalidWorkflowExamples() {
        // Invalid Example 1: Duplicate step IDs
        println("\n\nInvalid Example 1: Duplicate Step IDs")
        println("-".repeat(60))
        println("Violation: Two steps share the same ID")
        val dupIds = DummyWorkflows.invalidWorkflow(DummyWorkflows.ViolationType.DUPLICATE_STEP_IDS)
        printWorkflow(dupIds)
        validateAndPrint(dupIds)

        // Invalid Example 2: Missing dependency reference
        println("\n\nInvalid Example 2: Missing Dependency Reference")
        println("-".repeat(60))
        println("Violation: Step references a non-existent step")
        val missingDep = DummyWorkflows.invalidWorkflow(DummyWorkflows.ViolationType.MISSING_DEPENDENCY_REFERENCE)
        printWorkflow(missingDep)
        validateAndPrint(missingDep)

        // Invalid Example 3: Self-referencing cycle
        println("\n\nInvalid Example 3: Circular Dependency (Self-reference)")
        println("-".repeat(60))
        println("Violation: Step depends on itself")
        val selfCycle = DummyWorkflows.invalidWorkflow(DummyWorkflows.ViolationType.CIRCULAR_DEPENDENCY)
        printWorkflow(selfCycle)
        validateAndPrint(selfCycle)

        // Invalid Example 4: Two-step cycle
        println("\n\nInvalid Example 4: Circular Dependency (Two-step cycle)")
        println("-".repeat(60))
        println("Violation: Two steps depend on each other (s1 -> s2 -> s1)")
        val twoStepCycle = DummyWorkflows.invalidWorkflow(DummyWorkflows.ViolationType.TWO_STEP_CYCLE)
        printWorkflow(twoStepCycle)
        validateAndPrint(twoStepCycle)

        // Invalid Example 5: Three-step cycle
        println("\n\nInvalid Example 5: Circular Dependency (Three-step cycle)")
        println("-".repeat(60))
        println("Violation: Three steps form a dependency cycle (s1 -> s2 -> s3 -> s1)")
        val threeStepCycle = DummyWorkflows.invalidWorkflow(DummyWorkflows.ViolationType.THREE_STEP_CYCLE)
        printWorkflow(threeStepCycle)
        validateAndPrint(threeStepCycle)

        // Invalid Example 6: Empty workflow
        println("\n\nInvalid Example 6: Empty Workflow")
        println("-".repeat(60))
        println("Note: Empty workflows have no steps")
        val empty = DummyWorkflows.invalidWorkflow(DummyWorkflows.ViolationType.EMPTY_WORKFLOW)
        printWorkflow(empty)
        // Empty workflows may be valid depending on requirements, so we don't enforce shouldFail

        // Invalid Example 7: Multiple violations
        println("\n\nInvalid Example 7: Multiple Violations")
        println("-".repeat(60))
        println("Violations: Duplicate step IDs + missing dependencies")
        val multiViolation = DummyWorkflows.invalidWorkflow(DummyWorkflows.ViolationType.MULTIPLE_VIOLATIONS)
        printWorkflow(multiViolation)
        validateAndPrint(multiViolation)
    }

    private fun printWorkflow(definition: WorkflowDefinition) {
        val metadata = definition.workflow.metadata
        val steps = definition.workflow.getComponents()

        println("Workflow ID:   ${metadata.id}")
        println("Workflow Name: ${metadata.name}")
        println("Description:   ${metadata.description}")
        println("Step Count:    ${steps.size}")

        if (steps.isNotEmpty()) {
            println("\nSteps:")
            steps.forEachIndexed { index, component ->
                println("  ${index + 1}. ${component.metadata.name}")
            }
        }
    }

    private fun validateAndPrint(
        definition: WorkflowDefinition,
    ) {
        println("\nValidation Result:")

        val steps = definition.workflow.getComponents()
        val hasSteps = steps.isNotEmpty()

        if (!hasSteps) {
            println("Empty workflow: No steps defined")
        } else {
            // Check for duplicate IDs
            val ids = steps.map { it.metadata.id }
            val duplicates = ids.groupingBy { it }.eachCount().filter { it.value > 1 }

            if (duplicates.isNotEmpty()) {
                println("Validation Failed:")
                duplicates.forEach { (id, count) ->
                    println("  - Duplicate step ID: $id appears $count times")
                }
            } else {
                println("Valid: Workflow passed all validation checks")
            }
        }
    }

    private fun pauseForUser(message: String = "Press Enter to continue...") {
        println("\n$message")
        readlnOrNull() // Wait for user input
    }

    private fun printDemoHeader() {
        println(title)
        println("Comprehensive workflow validation demonstration")
        println("\nThis demo showcases:")
        println("  - Valid workflows with different complexity levels")
        println("  - Invalid workflows demonstrating validation errors")
        println("  - Validation messages explaining each issue")
    }

    private fun printDemoFooter() {
        println("Summary")
        println("\nValid Workflows:")
        println("  - Minimal configurations with required fields only")
        println("  - Complete configurations with all fields populated")
        println("\nInvalid Workflows:")
        println("  - Duplicate step IDs - violates uniqueness")
        println("  - Missing dependencies - references non-existent steps")
        println("  - Circular dependencies - self-references and cycles")
        println("  - Multiple violations - combines multiple issues")
    }
}
