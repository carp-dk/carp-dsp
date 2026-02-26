package carp.dsp.core.application.plan

import dk.cachet.carp.analytics.application.plan.PlanIssueSeverity
import dk.cachet.carp.analytics.domain.data.InputDataSpec
import dk.cachet.carp.analytics.domain.data.OutputDataSpec
import dk.cachet.carp.analytics.domain.data.RegistryDestination
import dk.cachet.carp.analytics.domain.data.StepOutputSource
import dk.cachet.carp.analytics.domain.environment.EnvironmentDefinition
import dk.cachet.carp.analytics.domain.tasks.CommandTaskDefinition
import dk.cachet.carp.analytics.domain.tasks.Literal
import dk.cachet.carp.analytics.domain.tasks.TaskDefinition
import dk.cachet.carp.analytics.domain.workflow.Step
import dk.cachet.carp.analytics.domain.workflow.StepMetadata
import dk.cachet.carp.analytics.domain.workflow.Version
import dk.cachet.carp.analytics.domain.workflow.Workflow
import dk.cachet.carp.analytics.domain.workflow.WorkflowDefinition
import dk.cachet.carp.analytics.domain.workflow.WorkflowMetadata
import dk.cachet.carp.common.application.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Comprehensive test suite for DefaultExecutionPlanner.
 *
 * Tests cover:
 * - Empty workflows
 * - Single step workflows
 * - Multi-step workflows with dependencies
 * - Environment validation
 * - Dependency graph construction
 * - Topological sorting
 * - Step compilation
 * - Error handling and issue collection
 * - Deterministic behavior
 */
class DefaultExecutionPlannerTest {

    private val planner = DefaultExecutionPlanner()

    // Mock task definition for testing
    private class MockTaskDefinition(
        override val id: UUID = UUID.randomUUID(),
        override val name: String,
        override val description: String? = null
    ) : TaskDefinition

    // Mock environment definition for testing
    private class MockEnvironmentDefinition(
        override val id: UUID = UUID.randomUUID(),
        override val name: String,
        override val dependencies: List<String> = emptyList(),
        override val environmentVariables: Map<String, String> = emptyMap()
    ) : EnvironmentDefinition

    private fun createWorkflowDefinition(
        name: String = "Test Workflow",
        steps: List<Step> = emptyList(),
        environments: List<MockEnvironmentDefinition> = emptyList()
    ): WorkflowDefinition {
        val workflowMetadata = WorkflowMetadata(
            id = UUID.randomUUID(),
            name = name,
            description = "Test workflow: $name",
            version = Version(1, 0)
        )

        val workflow = Workflow(workflowMetadata)
        steps.forEach { workflow.addComponent(it) }

        return WorkflowDefinition(
            workflow = workflow,
            environments = environments.associateBy { it.id }
        )
    }

    private fun createStep(
        name: String,
        environmentId: UUID = UUID.randomUUID(),
        inputs: List<InputDataSpec> = emptyList(),
        outputs: List<OutputDataSpec> = emptyList(),
        task: TaskDefinition? = null
    ): Step {
        return Step(
            metadata = StepMetadata(
                id = UUID.randomUUID(),
                name = name,
                description = "Test step: $name",
                version = Version(1, 0)
            ),
            task = task ?: MockTaskDefinition(name = "task-$name"),
            environmentId = environmentId,
            inputs = inputs,
            outputs = outputs
        )
    }

    private fun createCommandStep(
        name: String,
        environmentId: UUID = UUID.randomUUID(),
        executable: String = "echo",
        args: List<String> = listOf("test")
    ): Step {
        val commandTask = CommandTaskDefinition(
            id = UUID.randomUUID(),
            name = "command-$name",
            executable = executable,
            args = args.map { Literal(it) }
        )

        return createStep(name, environmentId, task = commandTask)
    }

    private fun createOutput(name: String): OutputDataSpec {
        return OutputDataSpec(
            id = UUID.randomUUID(),
            name = name,
            description = "Output $name",
            schema = null,
            destination = RegistryDestination(key = "output-$name")
        )
    }

    private fun createInput(name: String, source: StepOutputSource): InputDataSpec {
        return InputDataSpec(
            id = UUID.randomUUID(),
            name = name,
            description = "Input $name",
            schema = null,
            source = source,
            required = true,
            constraints = null
        )
    }

    @Test
    fun `plan with empty workflow returns empty execution plan`() {
        // Arrange
        val definition = createWorkflowDefinition("Empty Workflow")

        // Act
        val plan = planner.plan(definition)

        // Assert
        assertNotNull(plan)
        assertTrue(plan.steps.isEmpty())
        assertTrue(plan.issues.isEmpty())
        assertTrue(plan.requiredEnvironmentHandles.isEmpty())
        assertEquals(definition.workflow.metadata.id.toString(), plan.workflowId)
        assertNotNull(plan.planId)
    }

    @Test
    fun `plan with single command step succeeds`() {
        // Arrange
        val env = MockEnvironmentDefinition(name = "test-env")
        val step = createCommandStep("single-step", env.id)
        val definition = createWorkflowDefinition(
            "Single Step Workflow",
            steps = listOf(step),
            environments = listOf(env)
        )

        // Act
        val plan = planner.plan(definition)

        // Assert
        assertNotNull(plan)
        assertEquals(1, plan.steps.size)
        assertTrue(plan.issues.isEmpty())
        assertEquals(1, plan.requiredEnvironmentHandles.size)
        assertTrue(plan.requiredEnvironmentHandles.contains(env.id))

        val plannedStep = plan.steps[0]
        assertEquals(step.metadata.id, plannedStep.stepId)
        assertEquals(step.metadata.name, plannedStep.name)
    }

    @Test
    fun `plan detects missing environment references`() {
        // Arrange
        val missingEnvId = UUID.randomUUID()
        val step = createCommandStep("step-missing-env", missingEnvId)
        val definition = createWorkflowDefinition(
            "Missing Environment Workflow",
            steps = listOf(step),
            environments = emptyList() // No environments defined
        )

        // Act
        val plan = planner.plan(definition)

        // Assert
        assertNotNull(plan)
        assertEquals(1, plan.issues.size)

        val issue = plan.issues[0]
        assertEquals(PlanIssueSeverity.ERROR, issue.severity)
        assertEquals("MISSING_ENVIRONMENT", issue.code)
        assertEquals(step.metadata.id, issue.stepId)
        assertTrue(issue.message.contains(missingEnvId.toString()))
    }

    @Test
    fun `plan handles step dependencies correctly`() {
        // Arrange
        val env = MockEnvironmentDefinition(name = "test-env")

        // Producer step with output
        val output = createOutput("producer-output")
        val producer = createCommandStep("producer", env.id).copy(outputs = listOf(output))

        // Consumer step with input from producer
        val stepOutputSource = StepOutputSource(
            stepId = producer.metadata.id,
            outputId = output.id,
            metadata = emptyMap()
        )
        val input = createInput("consumer-input", stepOutputSource)
        val consumer = createCommandStep("consumer", env.id).copy(inputs = listOf(input))

        val definition = createWorkflowDefinition(
            "Dependency Workflow",
            steps = listOf(producer, consumer),
            environments = listOf(env)
        )

        // Act
        val plan = planner.plan(definition)

        // Assert
        assertNotNull(plan)
        assertEquals(2, plan.steps.size)
        assertTrue(plan.issues.isEmpty())

        // Verify topological ordering: producer should come before consumer
        val stepOrder = plan.steps.map { it.stepId }
        assertTrue(
            stepOrder.indexOf(producer.metadata.id) <
                  stepOrder.indexOf(consumer.metadata.id)
        )
    }

    @Test
    fun `plan handles unsupported task types`() {
        // Arrange
        val env = MockEnvironmentDefinition(name = "test-env")
        val step = createStep("unsupported-step", env.id) // Uses MockTaskDefinition
        val definition = createWorkflowDefinition(
            "Unsupported Task Workflow",
            steps = listOf(step),
            environments = listOf(env)
        )

        // Act
        val plan = planner.plan(definition)

        // Assert
        assertNotNull(plan)
        assertTrue(plan.steps.isEmpty()) // Step compilation failed
        assertEquals(1, plan.issues.size)

        val issue = plan.issues[0]
        assertEquals(PlanIssueSeverity.ERROR, issue.severity)
        assertEquals("UNSUPPORTED_TASK_TYPE", issue.code)
        assertEquals(step.metadata.id, issue.stepId)
    }

    @Test
    fun `plan is deterministic across multiple calls`() {
        // Arrange
        val env = MockEnvironmentDefinition(name = "test-env")
        val step1 = createCommandStep("step1", env.id)
        val step2 = createCommandStep("step2", env.id)
        val definition = createWorkflowDefinition(
            "Deterministic Workflow",
            steps = listOf(step1, step2),
            environments = listOf(env)
        )

        // Act
        val plan1 = planner.plan(definition)
        val plan2 = planner.plan(definition)

        // Assert
        assertEquals(plan1.workflowId, plan2.workflowId)
        assertEquals(plan1.steps.size, plan2.steps.size)
        assertEquals(plan1.issues.size, plan2.issues.size)
        assertEquals(plan1.requiredEnvironmentHandles.size, plan2.requiredEnvironmentHandles.size)

        // Step order should be the same (deterministic)
        val order1 = plan1.steps.map { it.stepId }
        val order2 = plan2.steps.map { it.stepId }
        assertEquals(order1, order2)

        // Environment handles should be in the same order (deterministic)
        assertEquals(plan1.requiredEnvironmentHandles, plan2.requiredEnvironmentHandles)
    }

    @Test
    fun `plan produces deterministic ordering with stable environment handle sorting`() {
        // Arrange - Use multiple environments to test sorting
        val env1 = MockEnvironmentDefinition(name = "env1") // UUID will be random
        val env2 = MockEnvironmentDefinition(name = "env2") // UUID will be random
        val env3 = MockEnvironmentDefinition(name = "env3") // UUID will be random

        val step1 = createCommandStep("step1", env2.id) // Use env2 first
        val step2 = createCommandStep("step2", env1.id) // Then env1
        val step3 = createCommandStep("step3", env3.id) // Then env3

        val definition = createWorkflowDefinition(
            "Environment Sorting Workflow",
            steps = listOf(step1, step2, step3),
            environments = listOf(env1, env2, env3)
        )

        // Act - Run multiple times
        val plan1 = planner.plan(definition)
        val plan2 = planner.plan(definition)
        val plan3 = planner.plan(definition)

        // Assert
        assertEquals(3, plan1.requiredEnvironmentHandles.size)

        // Environment handles should be deterministic across runs
        assertEquals(plan1.requiredEnvironmentHandles, plan2.requiredEnvironmentHandles)
        assertEquals(plan2.requiredEnvironmentHandles, plan3.requiredEnvironmentHandles)

        // Should contain all three environments
        assertTrue(plan1.requiredEnvironmentHandles.contains(env1.id))
        assertTrue(plan1.requiredEnvironmentHandles.contains(env2.id))
        assertTrue(plan1.requiredEnvironmentHandles.contains(env3.id))
    }

    @Test
    fun `plan collects multiple issues from different sources`() {
        // Arrange
        val validEnvId = UUID.randomUUID()
        val missingEnvId = UUID.randomUUID()

        // Step with missing environment
        val stepMissingEnv = createCommandStep("step-missing-env", missingEnvId)

        // Step with unsupported task
        val stepUnsupportedTask = createStep("step-unsupported", validEnvId)

        // Step with valid environment and command task
        val validStep = createCommandStep("valid-step", validEnvId)

        val definition = createWorkflowDefinition(
            "Multiple Issues Workflow",
            steps = listOf(stepMissingEnv, stepUnsupportedTask, validStep),
            environments = listOf(MockEnvironmentDefinition(id = validEnvId, name = "valid-env"))
        )

        // Act
        val plan = planner.plan(definition)

        // Assert
        assertNotNull(plan)
        assertEquals(2, plan.steps.size) // stepMissingEnv and validStep are planned (stepUnsupportedTask fails compilation)
        assertEquals(2, plan.issues.size) // Missing env + unsupported task

        assertTrue(plan.issues.any { it.code == "MISSING_ENVIRONMENT" })
        assertTrue(plan.issues.any { it.code == "UNSUPPORTED_TASK_TYPE" })
    }

    @Test
    fun `plan preserves declaration order for topological sorting`() {
        // Arrange
        val env = MockEnvironmentDefinition(name = "test-env")

        // Three independent steps - should maintain declaration order
        val step1 = createCommandStep("step1", env.id)
        val step2 = createCommandStep("step2", env.id)
        val step3 = createCommandStep("step3", env.id)

        val definition = createWorkflowDefinition(
            "Declaration Order Workflow",
            steps = listOf(step1, step2, step3),
            environments = listOf(env)
        )

        // Act
        val plan = planner.plan(definition)

        // Assert
        assertNotNull(plan)
        assertEquals(3, plan.steps.size)
        assertTrue(plan.issues.isEmpty())

        // Steps should maintain declaration order since they're independent
        val stepOrder = plan.steps.map { it.stepId }
        assertEquals(step1.metadata.id, stepOrder[0])
        assertEquals(step2.metadata.id, stepOrder[1])
        assertEquals(step3.metadata.id, stepOrder[2])
    }

    @Test
    fun `plan handles complex dependency graph`() {
        // Arrange
        val env = MockEnvironmentDefinition(name = "test-env")

        // Create a diamond dependency pattern: A → B,C → D
        val outputA = createOutput("outputA")
        val stepA = createCommandStep("stepA", env.id).copy(outputs = listOf(outputA))

        val sourceFromA1 = StepOutputSource(stepA.metadata.id, outputA.id, emptyMap())
        val sourceFromA2 = StepOutputSource(stepA.metadata.id, outputA.id, emptyMap())
        val inputB = createInput("inputB", sourceFromA1)
        val inputC = createInput("inputC", sourceFromA2)
        val outputB = createOutput("outputB")
        val outputC = createOutput("outputC")
        val stepB = createCommandStep("stepB", env.id).copy(inputs = listOf(inputB), outputs = listOf(outputB))
        val stepC = createCommandStep("stepC", env.id).copy(inputs = listOf(inputC), outputs = listOf(outputC))

        val sourceFromB = StepOutputSource(stepB.metadata.id, outputB.id, emptyMap())
        val sourceFromC = StepOutputSource(stepC.metadata.id, outputC.id, emptyMap())
        val inputD1 = createInput("inputD1", sourceFromB)
        val inputD2 = createInput("inputD2", sourceFromC)
        val stepD = createCommandStep("stepD", env.id).copy(inputs = listOf(inputD1, inputD2))

        val definition = createWorkflowDefinition(
            "Diamond Dependency Workflow",
            steps = listOf(stepA, stepB, stepC, stepD),
            environments = listOf(env)
        )

        // Act
        val plan = planner.plan(definition)

        // Assert
        assertNotNull(plan)
        assertEquals(4, plan.steps.size)
        assertTrue(plan.issues.isEmpty())

        // Verify topological ordering
        val stepOrder = plan.steps.map { it.stepId }
        val orderMap = stepOrder.mapIndexed { index, stepId -> stepId to index }.toMap()

        // A must come before B and C
        assertTrue(orderMap[stepA.metadata.id]!! < orderMap[stepB.metadata.id]!!)
        assertTrue(orderMap[stepA.metadata.id]!! < orderMap[stepC.metadata.id]!!)

        // B and C must come before D
        assertTrue(orderMap[stepB.metadata.id]!! < orderMap[stepD.metadata.id]!!)
        assertTrue(orderMap[stepC.metadata.id]!! < orderMap[stepD.metadata.id]!!)
    }

    @Test
    fun `plan handles missing dependency produces ERROR PlanIssue`() {
        // Arrange
        val env = MockEnvironmentDefinition(name = "test-env")

        // Create a step that references a non-existent producer step
        val nonExistentStepId = UUID.randomUUID()
        val nonExistentOutputId = UUID.randomUUID()
        val stepOutputSource = StepOutputSource(
            stepId = nonExistentStepId,
            outputId = nonExistentOutputId,
            metadata = emptyMap()
        )
        val input = createInput("missing-dependency-input", stepOutputSource)
        val consumer = createCommandStep("consumer-missing-dep", env.id).copy(inputs = listOf(input))

        val definition = createWorkflowDefinition(
            "Missing Dependency Workflow",
            steps = listOf(consumer),
            environments = listOf(env)
        )

        // Act
        val plan = planner.plan(definition)

        // Assert
        assertNotNull(plan)
        assertEquals(1, plan.steps.size) // Step still gets planned/compiled
        assertTrue(plan.issues.isNotEmpty()) // Has missing dependency issues

        // Plan should not be runnable due to ERROR issues
        assertTrue(plan.hasErrors())
        assertFalse(plan.isRunnable())

        // Should have MISSING_PRODUCER_STEP issue (from BindingsResolver or DependencyGraphBuilder)
        assertTrue(
            plan.issues.any {
            it.code == "MISSING_PRODUCER_STEP" || it.code == "DEPENDENCY_PRODUCER_NOT_FOUND"
        }
        )
        assertTrue(
            plan.issues.any { issue ->
            issue.stepId == consumer.metadata.id &&
            issue.message.contains(nonExistentStepId.toString())
        }
        )
    }

    @Test
    fun `plan handles cycle produces ERROR PlanIssue`() {
        // Arrange
        val env = MockEnvironmentDefinition(name = "test-env")

        // Create a 2-step cycle: A -> B -> A
        val outputA = createOutput("outputA")
        val outputB = createOutput("outputB")

        // Step A depends on Step B's output
        val sourceFromB = StepOutputSource(UUID.randomUUID(), outputB.id, emptyMap())
        val inputA = createInput("inputA", sourceFromB)
        val stepA = createCommandStep("stepA", env.id).copy(
            inputs = listOf(inputA),
            outputs = listOf(outputA)
        )

        // Step B depends on Step A's output (creating the cycle)
        val sourceFromA = StepOutputSource(stepA.metadata.id, outputA.id, emptyMap())
        val inputB = createInput("inputB", sourceFromA)
        val stepB = createCommandStep("stepB", env.id).copy(
            inputs = listOf(inputB),
            outputs = listOf(outputB)
        )

        // Update the sourceFromB to reference the actual stepB ID
        val correctedSourceFromB = StepOutputSource(stepB.metadata.id, outputB.id, emptyMap())
        val correctedStepA = stepA.copy(
            inputs = listOf(createInput("inputA", correctedSourceFromB))
        )

        val definition = createWorkflowDefinition(
            "Cycle Workflow",
            steps = listOf(correctedStepA, stepB),
            environments = listOf(env)
        )

        // Act
        val plan = planner.plan(definition)

        // Assert
        assertNotNull(plan)

        // Plan should have errors and not be runnable due to cycle
        assertTrue(plan.hasErrors())
        assertFalse(plan.isRunnable())

        // Should have cycle detection issue
        assertTrue(plan.issues.any { it.code == "WORKFLOW_CYCLE_DETECTED" })

        val cycleIssue = plan.issues.find { it.code == "WORKFLOW_CYCLE_DETECTED" }
        assertNotNull(cycleIssue)
        assertEquals(PlanIssueSeverity.ERROR, cycleIssue.severity)
        assertTrue(cycleIssue.message.contains("cycle"))
    }

    @Test
    fun `plan deduplicates required environment handles`() {
        // Arrange
        val env1 = MockEnvironmentDefinition(name = "env1")
        val env2 = MockEnvironmentDefinition(name = "env2")

        val step1 = createCommandStep("step1", env1.id)
        val step2 = createCommandStep("step2", env1.id) // Same environment as step1
        val step3 = createCommandStep("step3", env2.id)

        val definition = createWorkflowDefinition(
            "Environment Deduplication Workflow",
            steps = listOf(step1, step2, step3),
            environments = listOf(env1, env2)
        )

        // Act
        val plan = planner.plan(definition)

        // Assert
        assertNotNull(plan)
        assertEquals(3, plan.steps.size)
        assertEquals(2, plan.requiredEnvironmentHandles.size) // Should be deduplicated
        assertTrue(plan.requiredEnvironmentHandles.contains(env1.id))
        assertTrue(plan.requiredEnvironmentHandles.contains(env2.id))
    }
}
