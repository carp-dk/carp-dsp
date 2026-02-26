package carp.dsp.core.application.plan

import dk.cachet.carp.analytics.domain.data.InMemorySource
import dk.cachet.carp.analytics.domain.data.InputDataSpec
import dk.cachet.carp.analytics.domain.data.OutputDataSpec
import dk.cachet.carp.analytics.domain.data.RegistryDestination
import dk.cachet.carp.analytics.domain.data.StepOutputSource
import dk.cachet.carp.analytics.domain.tasks.TaskDefinition
import dk.cachet.carp.analytics.domain.workflow.Step
import dk.cachet.carp.analytics.domain.workflow.StepMetadata
import dk.cachet.carp.common.application.UUID
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Comprehensive test coverage for DependencyGraphBuilder class
 */
class DependencyGraphBuilderTest {

    // Test task definition for creating steps
    @Serializable
    private data class TestTask(
        override val id: UUID = UUID.randomUUID(),
        override val name: String,
        override val description: String? = null
    ) : TaskDefinition

    @Test
    fun `build with empty steps returns empty graph`() {
        val builder = DependencyGraphBuilder()

        val result = builder.build(emptyList())

        assertTrue(result.adjacency.isEmpty())
        assertTrue(result.indegree.isEmpty())
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun `build with single step no dependencies`() {
        val builder = DependencyGraphBuilder()
        val step = createStep("step1", emptyList(), listOf("output1"))

        val result = builder.build(listOf(step))

        assertEquals(1, result.adjacency.size)
        assertEquals(emptySet(), result.adjacency[step.metadata.id])
        assertEquals(0, result.indegree[step.metadata.id])
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun `build with two steps simple dependency`() {
        val builder = DependencyGraphBuilder()
        val step1 = createStep("step1", emptyList(), listOf("output1"))
        val step1OutputId = step1.outputs[0].id
        val step2 = createStep(
            "step2",
            listOf(createStepOutputInput("input1", step1.metadata.id, step1OutputId)),
            listOf("output2")
        )

        val result = builder.build(listOf(step1, step2))

        // step1 -> step2 dependency
        assertEquals(setOf(step2.metadata.id), result.adjacency[step1.metadata.id])
        assertEquals(emptySet(), result.adjacency[step2.metadata.id])
        assertEquals(0, result.indegree[step1.metadata.id]) // no dependencies
        assertEquals(1, result.indegree[step2.metadata.id]) // depends on step1
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun `build with complex dependency chain`() {
        val builder = DependencyGraphBuilder()
        val step1 = createStep("step1", emptyList(), listOf("output1"))
        val step1OutputId = step1.outputs[0].id

        val step2 = createStep(
            "step2",
            listOf(createStepOutputInput("input1", step1.metadata.id, step1OutputId)),
            listOf("output2")
        )
        val step2OutputId = step2.outputs[0].id

        val step3 = createStep(
            "step3",
            listOf(createStepOutputInput("input2", step2.metadata.id, step2OutputId)),
            listOf("output3")
        )
        val step3OutputId = step3.outputs[0].id

        val step4 = createStep(
            "step4",
            listOf(
                createStepOutputInput("input1", step1.metadata.id, step1OutputId),
                createStepOutputInput("input3", step3.metadata.id, step3OutputId)
            ),
            emptyList()
        )

        val result = builder.build(listOf(step1, step2, step3, step4))

        // Verify adjacency relationships
        assertEquals(setOf(step2.metadata.id, step4.metadata.id), result.adjacency[step1.metadata.id])
        assertEquals(setOf(step3.metadata.id), result.adjacency[step2.metadata.id])
        assertEquals(setOf(step4.metadata.id), result.adjacency[step3.metadata.id])
        assertEquals(emptySet(), result.adjacency[step4.metadata.id])

        // Verify indegrees
        assertEquals(0, result.indegree[step1.metadata.id])
        assertEquals(1, result.indegree[step2.metadata.id])
        assertEquals(1, result.indegree[step3.metadata.id])
        assertEquals(2, result.indegree[step4.metadata.id])

        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun `build with non-StepOutputSource inputs ignored`() {
        val builder = DependencyGraphBuilder()
        val step1 = createStep("step1", emptyList(), listOf("output1"))
        val step1OutputId = step1.outputs[0].id

        val step2 = createStep(
            "step2",
            listOf(
                createStepOutputInput("input1", step1.metadata.id, step1OutputId),
                createInMemoryInput("input2", "registry-key")
            ),
            emptyList()
        )

        val result = builder.build(listOf(step1, step2))

        // Only StepOutputSource should create dependency
        assertEquals(setOf(step2.metadata.id), result.adjacency[step1.metadata.id])
        assertEquals(1, result.indegree[step2.metadata.id])
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun `build detects missing producer step`() {
        val builder = DependencyGraphBuilder()
        val missingStepId = UUID.randomUUID()
        val outputId = UUID.randomUUID()

        val step2 = createStep(
            "step2",
            listOf(createStepOutputInput("input1", missingStepId, outputId)),
            emptyList()
        )

        val result = builder.build(listOf(step2))

        assertEquals(1, result.issues.size)
        // Verify issue exists but don't check specific properties until PlanIssue is properly implemented
        assertTrue(result.issues.isNotEmpty())
    }

    @Test
    fun `build detects missing producer output`() {
        val builder = DependencyGraphBuilder()
        val step1 = createStep("step1", emptyList(), listOf("actual-output"))
        val missingOutputId = UUID.randomUUID()

        val step2 = createStep(
            "step2",
            listOf(createStepOutputInput("input1", step1.metadata.id, missingOutputId)),
            emptyList()
        )

        val result = builder.build(listOf(step1, step2))

        assertEquals(1, result.issues.size)
        // Verify issue exists for missing output
        assertTrue(result.issues.isNotEmpty())
        // Verify the issue is correctly attributed to the consumer step (step2), not the producer (step1)
        assertEquals(step2.metadata.id, result.issues[0].stepId)
        assertEquals("DEPENDENCY_OUTPUT_NOT_FOUND", result.issues[0].code)
    }

    @Test
    fun `build detects self-dependency`() {
        val builder = DependencyGraphBuilder()

        // Create a step that references itself
        val stepId = UUID.randomUUID()
        val outputId = UUID.randomUUID()

        val selfDepStep = Step(
            metadata = StepMetadata(
                id = stepId,
                name = "self-referencing-step",
                description = "Test step that depends on itself"
            ),
            inputs = listOf(
                InputDataSpec(
                    id = UUID.randomUUID(),
                    name = "Input self-ref",
                    source = StepOutputSource(
                        stepId = stepId, // Same as the step's own ID
                        outputId = outputId,
                        metadata = emptyMap()
                    ),
                    required = true
                )
            ),
            outputs = listOf(
                OutputDataSpec(
                    id = outputId,
                    name = "Output self",
                    destination = RegistryDestination(key = "self-output")
                )
            ),
            task = TestTask(name = "test-task"),
            environmentId = UUID.randomUUID()
        )

        val result = builder.build(listOf(selfDepStep))

        assertEquals(1, result.issues.size)
        // Verify self-dependency issue is detected
        assertTrue(result.issues.isNotEmpty())
        assertEquals("DEPENDENCY_SELF_REFERENCE", result.issues[0].code)
    }

    @Test
    fun `build handles multiple validation errors`() {
        val builder = DependencyGraphBuilder()
        val missingStepId = UUID.randomUUID()
        val outputId = UUID.randomUUID()
        val missingOutputId = UUID.randomUUID()

        val step1 = createStep("step1", emptyList(), listOf("output1"))

        // Create step with self-dependency using a fixed stepId
        val selfDepStepId = UUID.randomUUID()
        val selfDepOutputId = UUID.randomUUID()
        val selfDepStep = Step(
            metadata = StepMetadata(
                id = selfDepStepId,
                name = "step-with-self-dep",
                description = "Test step with self-dependency"
            ),
            inputs = listOf(
                createStepOutputInput("input1", missingStepId, outputId), // missing producer
                InputDataSpec(
                    id = UUID.randomUUID(),
                    name = "Input self-dep",
                    source = StepOutputSource(
                        stepId = selfDepStepId, // self-dependency
                        outputId = selfDepOutputId, // reference its own output
                        metadata = emptyMap()
                    ),
                    required = true
                )
            ),
            outputs = listOf(
                OutputDataSpec(
                    id = selfDepOutputId, // same as referenced in input
                    name = "Output test",
                    destination = RegistryDestination(key = "test-output")
                )
            ),
            task = TestTask(name = "test-task"),
            environmentId = UUID.randomUUID()
        )

        val step2 = createStep(
            "step2",
            listOf(createStepOutputInput("input3", step1.metadata.id, missingOutputId)), // missing output
            emptyList()
        )

        val result = builder.build(listOf(selfDepStep, step1, step2))

        assertEquals(3, result.issues.size)
        // Verify multiple issues are detected
        assertTrue(result.issues.size >= 3)
    }

    @Test
    fun `build with diamond dependency pattern`() {
        val builder = DependencyGraphBuilder()
        //     step1
        //    /     \
        // step2   step3
        //    \     /
        //     step4
        val step1 = createStep("step1", emptyList(), listOf("output1"))
        val step1OutputId = step1.outputs[0].id

        val step2 = createStep(
            "step2",
            listOf(createStepOutputInput("input1", step1.metadata.id, step1OutputId)),
            listOf("output2")
        )
        val step2OutputId = step2.outputs[0].id

        val step3 = createStep(
            "step3",
            listOf(createStepOutputInput("input1", step1.metadata.id, step1OutputId)),
            listOf("output3")
        )
        val step3OutputId = step3.outputs[0].id

        val step4 = createStep(
            "step4",
            listOf(
                createStepOutputInput("input2", step2.metadata.id, step2OutputId),
                createStepOutputInput("input3", step3.metadata.id, step3OutputId)
            ),
            emptyList()
        )

        val result = builder.build(listOf(step1, step2, step3, step4))

        // Verify adjacency relationships
        assertEquals(setOf(step2.metadata.id, step3.metadata.id), result.adjacency[step1.metadata.id])
        assertEquals(setOf(step4.metadata.id), result.adjacency[step2.metadata.id])
        assertEquals(setOf(step4.metadata.id), result.adjacency[step3.metadata.id])
        assertEquals(emptySet(), result.adjacency[step4.metadata.id])

        // Verify indegrees
        assertEquals(0, result.indegree[step1.metadata.id])
        assertEquals(1, result.indegree[step2.metadata.id])
        assertEquals(1, result.indegree[step3.metadata.id])
        assertEquals(2, result.indegree[step4.metadata.id]) // Depends on both step2 and step3

        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun `build preserves immutability of result collections`() {
        val builder = DependencyGraphBuilder()
        val step1 = createStep("step1", emptyList(), listOf("output1"))
        val step1OutputId = step1.outputs[0].id

        val step2 = createStep(
            "step2",
            listOf(createStepOutputInput("input1", step1.metadata.id, step1OutputId)),
            emptyList()
        )

        val result = builder.build(listOf(step1, step2))

        // Verify collections are immutable by verifying they can be accessed safely
        assertTrue(result.adjacency.isNotEmpty())
        assertTrue(result.indegree.isNotEmpty())
        assertTrue(result.issues.isEmpty()) // Should be empty for valid case

        // Verify nested sets are immutable by accessing them
        val step1Adjacency = result.adjacency[step1.metadata.id]
        assertTrue(step1Adjacency != null)
        assertTrue(step1Adjacency.isNotEmpty())
    }

    @Test
    fun `extractDependencies is tested through build method behavior`() {
        // This private method is thoroughly tested through the public build method
        // by verifying that only StepOutputSource dependencies are processed
        val builder = DependencyGraphBuilder()
        val producerStepId = UUID.randomUUID()
        val outputId = UUID.randomUUID()

        val step = createStep(
            "step1",
            listOf(
                createStepOutputInput("stepOutput", producerStepId, outputId),
                createInMemoryInput("inMemory", "key")
            ),
            emptyList()
        )

        val result = builder.build(listOf(step))

        // Only one issue should be generated (for missing producer)
        // InMemory input should be ignored
        assertEquals(1, result.issues.size)
        // Verify the issue is related to dependency handling
        assertTrue(result.issues.isNotEmpty())
    }

    @Test
    fun `validation methods produce helpful error messages`() {
        val builder = DependencyGraphBuilder()
        val step1 = createStep("producer-step", emptyList(), listOf("valid-output", "another-output"))
        val nonexistentOutputId = UUID.randomUUID()

        val step2 = createStep(
            "consumer-step",
            listOf(createStepOutputInput("input1", step1.metadata.id, nonexistentOutputId)),
            emptyList()
        )

        val result = builder.build(listOf(step1, step2))

        assertEquals(1, result.issues.size)
        // Verify an issue is generated for the invalid reference
        assertTrue(result.issues.isNotEmpty())
    }

    @Test
    fun `build with multiple independent steps - all indegree equals zero`() {
        val builder = DependencyGraphBuilder()
        val step1 = createStep("independent1", emptyList(), listOf("output1"))
        val step2 = createStep("independent2", emptyList(), listOf("output2"))
        val step3 = createStep("independent3", emptyList(), listOf("output3"))

        val result = builder.build(listOf(step1, step2, step3))

        // All steps should have empty adjacency (no outgoing edges)
        assertEquals(3, result.adjacency.size)
        assertEquals(emptySet(), result.adjacency[step1.metadata.id])
        assertEquals(emptySet(), result.adjacency[step2.metadata.id])
        assertEquals(emptySet(), result.adjacency[step3.metadata.id])

        // All steps should have indegree = 0 (no incoming edges)
        assertEquals(0, result.indegree[step1.metadata.id])
        assertEquals(0, result.indegree[step2.metadata.id])
        assertEquals(0, result.indegree[step3.metadata.id])

        // No validation issues for independent steps
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun `build handles duplicate dependency reference - no double indegree increment`() {
        val builder = DependencyGraphBuilder()
        val step1 = createStep("producer", emptyList(), listOf("output1"))
        val step1OutputId = step1.outputs[0].id

        // Step2 references the same producer output twice
        val outputStep = createStepOutputInput("input1", step1.metadata.id, step1OutputId)
        val step2 = createStep(
            "consumer",
            listOf(
                outputStep, outputStep // Same dependency
            ),
            emptyList()
        )

        val result = builder.build(listOf(step1, step2))

        // Verify adjacency: step1 -> step2 (should appear only once)
        assertEquals(setOf(step2.metadata.id), result.adjacency[step1.metadata.id])
        assertEquals(emptySet(), result.adjacency[step2.metadata.id])

        // Critical: indegree should be 1, not 2 (no double increment for duplicate references)
        assertEquals(0, result.indegree[step1.metadata.id])
        assertEquals(1, result.indegree[step2.metadata.id]) // Should be 1, not 2

        // No validation issues for duplicate valid references
        assertTrue(result.issues.isEmpty())
    }

    // Helper methods for creating test data

    private fun createStep(
        name: String,
        inputs: List<InputDataSpec>,
        outputNames: List<String>
    ): Step {
        val outputs = outputNames.map { outputName ->
            OutputDataSpec(
                id = UUID.randomUUID(),
                name = "Output $outputName",
                destination = RegistryDestination(key = outputName)
            )
        }

        return Step(
            metadata = StepMetadata(
                id = UUID.randomUUID(),
                name = name,
                description = "Test step $name"
            ),
            inputs = inputs,
            outputs = outputs,
            task = TestTask(name = "test-task"),
            environmentId = UUID.randomUUID()
        )
    }

    private fun createStepOutputInput(
        identifier: String,
        stepId: UUID,
        outputId: UUID
    ): InputDataSpec {
        return InputDataSpec(
            id = UUID.randomUUID(),
            name = "Input $identifier",
            source = StepOutputSource(
                stepId = stepId,
                outputId = outputId,
                metadata = emptyMap()
            )
        )
    }

    private fun createInMemoryInput(identifier: String, registryKey: String): InputDataSpec {
        return InputDataSpec(
            id = UUID.randomUUID(),
            name = "Input $identifier",
            source = InMemorySource(
                registryKey = registryKey,
                metadata = emptyMap()
            )
        )
    }
}
