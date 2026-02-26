package carp.dsp.core.application.plan

import dk.cachet.carp.analytics.application.plan.CommandSpec
import dk.cachet.carp.analytics.application.plan.DataRef
import dk.cachet.carp.analytics.application.plan.PlanIssue
import dk.cachet.carp.analytics.application.plan.PlanIssueSeverity
import dk.cachet.carp.analytics.application.plan.PlannedStep
import dk.cachet.carp.analytics.application.plan.ResolvedBindings
import dk.cachet.carp.analytics.domain.data.InMemorySource
import dk.cachet.carp.analytics.domain.data.InputDataSpec
import dk.cachet.carp.analytics.domain.data.OutputDataSpec
import dk.cachet.carp.analytics.domain.data.RegistryDestination
import dk.cachet.carp.analytics.domain.data.StepOutputSource
import dk.cachet.carp.analytics.domain.tasks.TaskDefinition
import dk.cachet.carp.analytics.domain.workflow.Step
import dk.cachet.carp.analytics.domain.workflow.StepMetadata
import dk.cachet.carp.analytics.domain.workflow.Version
import dk.cachet.carp.common.application.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Comprehensive test suite for BindingsResolver.
 *
 * Tests cover P0 specifications:
 * - Output resolution to deterministic DataRefs
 * - StepOutputSource input resolution
 * - Error handling for missing producers/outputs
 * - Unsupported input source types
 * - Pure, side-effect free behavior
 */
class BindingsResolverTest {

    private val resolver = BindingsResolver()

    // Mock task definition for testing
    private class MockTaskDefinition(
        override val id: UUID = UUID.randomUUID(),
        override val name: String,
        override val description: String? = null
    ) : TaskDefinition

    private fun createStep(
        name: String,
        inputs: List<InputDataSpec> = emptyList(),
        outputs: List<OutputDataSpec> = emptyList()
    ): Step {
        return Step(
            metadata = StepMetadata(
                id = UUID.randomUUID(),
                name = name,
                description = "Test step: $name",
                version = Version(1, 0)
            ),
            task = MockTaskDefinition(name = "task-$name"),
            environmentId = UUID.randomUUID(),
            inputs = inputs,
            outputs = outputs
        )
    }

    private fun createOutputSpec(name: String): OutputDataSpec {
        return OutputDataSpec(
            id = UUID.randomUUID(),
            name = name,
            description = "Output $name",
            schema = null, // P0: Using null for DataSchema
            destination = RegistryDestination(key = "output-$name")
        )
    }

    private fun createInputSpec(name: String, source: dk.cachet.carp.analytics.domain.data.DataSource): InputDataSpec {
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


    private fun createStepOutputSource(stepId: UUID, outputId: UUID): StepOutputSource {
        return StepOutputSource(
            stepId = stepId,
            outputId = outputId,
            metadata = emptyMap()
        )
    }

    private fun createPlannedStep(stepId: UUID, bindings: ResolvedBindings): PlannedStep {
        return PlannedStep(
            stepId = stepId,
            name = "Planned Step",
            process = CommandSpec("echo", listOf("test")),
            bindings = bindings,
            environmentDefinitionId = UUID.randomUUID()
        )
    }

    @Test
    fun `resolve creates deterministic output DataRefs`() {
        // Arrange
        val output1 = createOutputSpec("output1")
        val output2 = createOutputSpec("output2")
        val step = createStep("test-step", outputs = listOf(output1, output2))
        val issues = mutableListOf<PlanIssue>()

        // Act
        val bindings = resolver.resolve(step, emptyMap(), issues)

        // Assert
        assertTrue(issues.isEmpty())
        assertEquals(2, bindings.outputs.size)

        val output1Ref = bindings.outputs[output1.id]
        assertNotNull(output1Ref)
        assertEquals(output1.id, output1Ref.id)
        assertEquals("unknown", output1Ref.type) // Schema is null, so type becomes "unknown"

        val output2Ref = bindings.outputs[output2.id]
        assertNotNull(output2Ref)
        assertEquals(output2.id, output2Ref.id)
        assertEquals("unknown", output2Ref.type) // Schema is null, so type becomes "unknown"
    }

    @Test
    fun `resolve handles outputs with null schema`() {
        // Arrange
        val output = createOutputSpec("output")
        val step = createStep("test-step", outputs = listOf(output))
        val issues = mutableListOf<PlanIssue>()

        // Act
        val bindings = resolver.resolve(step, emptyMap(), issues)

        // Assert
        assertTrue(issues.isEmpty())
        val outputRef = bindings.outputs[output.id]
        assertNotNull(outputRef)
        assertEquals(output.id, outputRef.id)
        assertEquals("unknown", outputRef.type)
    }

    @Test
    fun `resolve handles step with no inputs or outputs`() {
        // Arrange
        val step = createStep("empty-step")
        val issues = mutableListOf<PlanIssue>()

        // Act
        val bindings = resolver.resolve(step, emptyMap(), issues)

        // Assert
        assertTrue(issues.isEmpty())
        assertTrue(bindings.inputs.isEmpty())
        assertTrue(bindings.outputs.isEmpty())
    }

    @Test
    fun `resolve successfully resolves StepOutputSource input`() {
        // Arrange
        val producerStepId = UUID.randomUUID()
        val producerOutputId = UUID.randomUUID()
        val producerOutputRef = DataRef(producerOutputId, "text/plain")
        val producerBindings = ResolvedBindings(
            inputs = emptyMap(),
            outputs = mapOf(producerOutputId to producerOutputRef)
        )
        val plannedProducer = createPlannedStep(producerStepId, producerBindings)
        val plannedSteps = mapOf(producerStepId to plannedProducer)

        val stepOutputSource = createStepOutputSource(producerStepId, producerOutputId)
        val input = createInputSpec("input1", stepOutputSource)
        val consumerStep = createStep("consumer", inputs = listOf(input))
        val issues = mutableListOf<PlanIssue>()

        // Act
        val bindings = resolver.resolve(consumerStep, plannedSteps, issues)

        // Assert
        assertTrue(issues.isEmpty())
        assertEquals(1, bindings.inputs.size)
        assertEquals(producerOutputRef, bindings.inputs[input.id])
    }

    @Test
    fun `resolve emits error for missing producer step`() {
        // Arrange
        val missingStepId = UUID.randomUUID()
        val outputId = UUID.randomUUID()
        val stepOutputSource = createStepOutputSource(missingStepId, outputId)
        val input = createInputSpec("input1", stepOutputSource)
        val consumerStep = createStep("consumer", inputs = listOf(input))
        val issues = mutableListOf<PlanIssue>()

        // Act
        val bindings = resolver.resolve(consumerStep, emptyMap(), issues)

        // Assert
        assertEquals(1, issues.size)
        val issue = issues[0]
        assertEquals(PlanIssueSeverity.ERROR, issue.severity)
        assertEquals("MISSING_PRODUCER_STEP", issue.code)
        assertEquals(consumerStep.metadata.id, issue.stepId)
        assertTrue(issue.message.contains(missingStepId.toString()))
        assertTrue(issue.message.contains(input.id.toString()))

        // Bindings should still be created but without the missing input
        assertTrue(bindings.inputs.isEmpty())
    }

    @Test
    fun `resolve emits error for missing producer output`() {
        // Arrange
        val producerStepId = UUID.randomUUID()
        val existingOutputId = UUID.randomUUID()
        val missingOutputId = UUID.randomUUID()

        val producerBindings = ResolvedBindings(
            inputs = emptyMap(),
            outputs = mapOf(existingOutputId to DataRef(existingOutputId, "text/plain"))
        )
        val plannedProducer = createPlannedStep(producerStepId, producerBindings)
        val plannedSteps = mapOf(producerStepId to plannedProducer)

        val stepOutputSource = createStepOutputSource(producerStepId, missingOutputId)
        val input = createInputSpec("input1", stepOutputSource)
        val consumerStep = createStep("consumer", inputs = listOf(input))
        val issues = mutableListOf<PlanIssue>()

        // Act
        val bindings = resolver.resolve(consumerStep, plannedSteps, issues)

        // Assert
        assertEquals(1, issues.size)
        val issue = issues[0]
        assertEquals(PlanIssueSeverity.ERROR, issue.severity)
        assertEquals("MISSING_PRODUCER_OUTPUT", issue.code)
        assertEquals(consumerStep.metadata.id, issue.stepId)
        assertTrue(issue.message.contains(missingOutputId.toString()))
        assertTrue(issue.message.contains(producerStepId.toString()))
        assertTrue(issue.message.contains(input.id.toString()))

        // Bindings should still be created but without the missing input
        assertTrue(bindings.inputs.isEmpty())
    }

    @Test
    fun `resolve emits error for unsupported input source type`() {
        // Use InMemorySource as a concrete DataSource that's not StepOutputSource
        val unsupportedSource = InMemorySource(
            registryKey = "test-registry-key",
            metadata = emptyMap()
        )

        val input = createInputSpec("input1", unsupportedSource)
        val consumerStep = createStep("consumer", inputs = listOf(input))
        val issues = mutableListOf<PlanIssue>()

        // Act
        val bindings = resolver.resolve(consumerStep, emptyMap(), issues)

        // Assert
        assertEquals(1, issues.size)
        val issue = issues[0]
        assertEquals(PlanIssueSeverity.ERROR, issue.severity)
        assertEquals("UNSUPPORTED_INPUT_SOURCE", issue.code)
        assertEquals(consumerStep.metadata.id, issue.stepId)
        assertTrue(issue.message.contains("InMemorySource"))
        assertTrue(issue.message.contains("Only StepOutputSource is supported"))

        // Bindings should still be created but without the unsupported input
        assertTrue(bindings.inputs.isEmpty())
    }

    @Test
    fun `resolve handles mixed successful and failed input resolution`() {
        // Arrange
        val producerStepId = UUID.randomUUID()
        val validOutputId = UUID.randomUUID()
        val missingOutputId = UUID.randomUUID()

        val producerBindings = ResolvedBindings(
            inputs = emptyMap(),
            outputs = mapOf(validOutputId to DataRef(validOutputId, "text/plain"))
        )
        val plannedProducer = createPlannedStep(producerStepId, producerBindings)
        val plannedSteps = mapOf(producerStepId to plannedProducer)

        val validSource = createStepOutputSource(producerStepId, validOutputId)
        val invalidSource = createStepOutputSource(producerStepId, missingOutputId)
        val unsupportedSource = InMemorySource(
            registryKey = "test-unsupported-key",
            metadata = emptyMap()
        )

        val validInput = createInputSpec("valid-input", validSource)
        val invalidInput = createInputSpec("invalid-input", invalidSource)
        val unsupportedInput = createInputSpec("unsupported-input", unsupportedSource)

        val consumerStep = createStep(
            "consumer",
            inputs = listOf(validInput, invalidInput, unsupportedInput)
        )
        val issues = mutableListOf<PlanIssue>()

        // Act
        val bindings = resolver.resolve(consumerStep, plannedSteps, issues)

        // Assert
        assertEquals(2, issues.size) // One for missing output, one for unsupported source
        assertTrue(issues.any { it.code == "MISSING_PRODUCER_OUTPUT" })
        assertTrue(issues.any { it.code == "UNSUPPORTED_INPUT_SOURCE" })

        // Only the valid input should be resolved
        assertEquals(1, bindings.inputs.size)
        assertEquals(DataRef(validOutputId, "text/plain"), bindings.inputs[validInput.id])
    }

    @Test
    fun `resolve is deterministic across multiple calls`() {
        // Arrange
        val output = createOutputSpec("output")
        val step = createStep("test-step", outputs = listOf(output))
        val issues1 = mutableListOf<PlanIssue>()
        val issues2 = mutableListOf<PlanIssue>()

        // Act
        val bindings1 = resolver.resolve(step, emptyMap(), issues1)
        val bindings2 = resolver.resolve(step, emptyMap(), issues2)

        // Assert
        assertEquals(bindings1, bindings2)
        assertEquals(issues1.size, issues2.size)
    }

    @Test
    fun `resolve does not modify input parameters`() {
        // Arrange
        val originalPlannedSteps = mapOf<UUID, PlannedStep>()
        val originalIssues = mutableListOf<PlanIssue>()
        val step = createStep("test-step")

        // Act
        resolver.resolve(step, originalPlannedSteps, originalIssues)

        // Assert
        assertTrue(originalIssues.isEmpty()) // No validation errors for this simple step
    }

    @Test
    fun `resolve returns ResolvedBindings even with errors`() {
        // Arrange - test with a missing producer output error
        val producerStepId = UUID.randomUUID()
        val missingOutputId = UUID.randomUUID()
        val stepOutputSource = createStepOutputSource(producerStepId, missingOutputId)
        val input = createInputSpec("input1", stepOutputSource)
        val output = createOutputSpec("output1")
        val step = createStep("error-step", inputs = listOf(input), outputs = listOf(output))
        val issues = mutableListOf<PlanIssue>()

        // Act
        val bindings = resolver.resolve(step, emptyMap(), issues)

        // Assert
        assertNotNull(bindings)
        assertTrue(issues.isNotEmpty()) // Has errors
        assertEquals(1, bindings.outputs.size) // Outputs still created
        assertTrue(bindings.inputs.isEmpty()) // Input failed to resolve
    }

    @Test
    fun `resolve handles complex step with multiple inputs and outputs`() {
        // Arrange
        val producer1Id = UUID.randomUUID()
        val producer2Id = UUID.randomUUID()
        val output1Id = UUID.randomUUID()
        val output2Id = UUID.randomUUID()

        val producer1Bindings = ResolvedBindings(
            inputs = emptyMap(),
            outputs = mapOf(output1Id to DataRef(output1Id, "text/csv"))
        )
        val producer2Bindings = ResolvedBindings(
            inputs = emptyMap(),
            outputs = mapOf(output2Id to DataRef(output2Id, "application/json"))
        )

        val plannedSteps = mapOf(
            producer1Id to createPlannedStep(producer1Id, producer1Bindings),
            producer2Id to createPlannedStep(producer2Id, producer2Bindings)
        )

        val input1 = createInputSpec("csv-input", createStepOutputSource(producer1Id, output1Id))
        val input2 = createInputSpec("json-input", createStepOutputSource(producer2Id, output2Id))
        val output1 = createOutputSpec("result1")
        val output2 = createOutputSpec("result2")

        val consumerStep = createStep(
            "complex-consumer",
            inputs = listOf(input1, input2),
            outputs = listOf(output1, output2)
        )
        val issues = mutableListOf<PlanIssue>()

        // Act
        val bindings = resolver.resolve(consumerStep, plannedSteps, issues)

        // Assert
        assertTrue(issues.isEmpty())
        assertEquals(2, bindings.inputs.size)
        assertEquals(2, bindings.outputs.size)

        assertEquals(DataRef(output1Id, "text/csv"), bindings.inputs[input1.id])
        assertEquals(DataRef(output2Id, "application/json"), bindings.inputs[input2.id])
        assertEquals(DataRef(output1.id, "unknown"), bindings.outputs[output1.id]) // Schema is null
        assertEquals(DataRef(output2.id, "unknown"), bindings.outputs[output2.id]) // Schema is null
    }

    @Test
    fun `helper methods create valid objects with all parameters`() {
        // Test createStepOutputSource with different stepIds and outputIds
        val stepId1 = UUID.randomUUID()
        val stepId2 = UUID.randomUUID()
        val outputId1 = UUID.randomUUID()
        val outputId2 = UUID.randomUUID()

        val source1 = createStepOutputSource(stepId1, outputId1)
        val source2 = createStepOutputSource(stepId2, outputId2)

        // Verify sources are created with correct parameters
        assertEquals(stepId1, source1.stepId)
        assertEquals(outputId1, source1.outputId)
        assertTrue(source1.metadata.isEmpty())

        assertEquals(stepId2, source2.stepId)
        assertEquals(outputId2, source2.outputId)
        assertTrue(source2.metadata.isEmpty())

        // Test createInputSpec with different sources
        val input1 = createInputSpec("test-input-1", source1)
        val input2 = createInputSpec("test-input-2", source2)

        assertEquals("test-input-1", input1.name)
        assertEquals("Input test-input-1", input1.description)
        assertEquals(source1, input1.source)
        assertTrue(input1.required)
        assertEquals(null, input1.constraints)
        assertEquals(null, input1.schema)

        assertEquals("test-input-2", input2.name)
        assertEquals("Input test-input-2", input2.description)
        assertEquals(source2, input2.source)

        // Test createStep with different configurations
        val step1 = createStep("step-1")
        val step2 = createStep("step-2", inputs = listOf(input1), outputs = listOf(createOutputSpec("out1")))

        assertEquals("step-1", step1.metadata.name)
        assertEquals("Test step: step-1", step1.metadata.description)
        assertEquals(Version(1, 0), step1.metadata.version)
        assertTrue(step1.inputs.isEmpty())
        assertTrue(step1.outputs.isEmpty())
        assertEquals("task-step-1", step1.task.name)

        assertEquals("step-2", step2.metadata.name)
        assertEquals("Test step: step-2", step2.metadata.description)
        assertEquals(1, step2.inputs.size)
        assertEquals(1, step2.outputs.size)
        assertEquals("task-step-2", step2.task.name)
    }

    @Test
    fun `helper methods handle edge cases and variations`() {
        // Test createOutputSpec with different names
        val output1 = createOutputSpec("simple")
        val output2 = createOutputSpec("complex-output-name-123")

        assertEquals("simple", output1.name)
        assertEquals("Output simple", output1.description)
        assertEquals("output-simple", (output1.destination as RegistryDestination).key)
        assertEquals(null, output1.schema)
        assertNotNull(output1.id)

        assertEquals("complex-output-name-123", output2.name)
        assertEquals("Output complex-output-name-123", output2.description)
        assertEquals("output-complex-output-name-123", (output2.destination as RegistryDestination).key)

        // Test createPlannedStep with different configurations
        val bindings1 = ResolvedBindings(emptyMap(), emptyMap())
        val bindings2 = ResolvedBindings(
            inputs = mapOf(UUID.randomUUID() to DataRef(UUID.randomUUID(), "input-type")),
            outputs = mapOf(UUID.randomUUID() to DataRef(UUID.randomUUID(), "output-type"))
        )

        val stepId1 = UUID.randomUUID()
        val stepId2 = UUID.randomUUID()

        val plannedStep1 = createPlannedStep(stepId1, bindings1)
        val plannedStep2 = createPlannedStep(stepId2, bindings2)

        assertEquals(stepId1, plannedStep1.stepId)
        assertEquals("Planned Step", plannedStep1.name)
        assertEquals(bindings1, plannedStep1.bindings)
        assertTrue(plannedStep1.process is CommandSpec)
        assertEquals("echo", (plannedStep1.process as CommandSpec).executable)
        assertEquals(listOf("test"), (plannedStep1.process as CommandSpec).args)

        assertEquals(stepId2, plannedStep2.stepId)
        assertEquals(bindings2, plannedStep2.bindings)
        assertEquals(1, plannedStep2.bindings.inputs.size)
        assertEquals(1, plannedStep2.bindings.outputs.size)
    }

    @Test
    fun `MockTaskDefinition handles different configurations`() {
        // Test MockTaskDefinition with different parameter combinations
        val task1 = MockTaskDefinition(name = "basic-task")
        val task2 = MockTaskDefinition(
            id = UUID.randomUUID(),
            name = "detailed-task",
            description = "A task with description"
        )
        val task3 = MockTaskDefinition(
            name = "task-with-custom-id",
            description = null
        )

        assertEquals("basic-task", task1.name)
        assertEquals(null, task1.description)
        assertNotNull(task1.id)

        assertEquals("detailed-task", task2.name)
        assertEquals("A task with description", task2.description)
        assertNotNull(task2.id)

        assertEquals("task-with-custom-id", task3.name)
        assertEquals(null, task3.description)
        assertNotNull(task3.id)

        // Test that all IDs are unique
        assertTrue(task1.id != task2.id)
        assertTrue(task1.id != task3.id)
        assertTrue(task2.id != task3.id)

        // Test creating steps with these tasks directly
        val step1 = createStep("step-with-task-1")
        val step2 = createStep("step-with-task-2")

        // Verify task names are generated from step names
        assertEquals("task-step-with-task-1", step1.task.name)
        assertEquals("task-step-with-task-2", step2.task.name)
        assertNotNull(step1.task.id)
        assertNotNull(step2.task.id)
        assertTrue(step1.task.id != step2.task.id)
    }
}
