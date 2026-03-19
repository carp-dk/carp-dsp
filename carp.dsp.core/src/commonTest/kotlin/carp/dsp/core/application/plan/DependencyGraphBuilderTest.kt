package carp.dsp.core.application.plan

import dk.cachet.carp.analytics.domain.data.FileFormat
import dk.cachet.carp.analytics.domain.data.FileLocation
import dk.cachet.carp.analytics.domain.data.InputDataSpec
import dk.cachet.carp.analytics.domain.data.OutputDataSpec
import dk.cachet.carp.analytics.domain.tasks.TaskDefinition
import dk.cachet.carp.analytics.domain.workflow.Step
import dk.cachet.carp.analytics.domain.workflow.StepMetadata
import dk.cachet.carp.analytics.domain.workflow.Version
import dk.cachet.carp.common.application.UUID
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DependencyGraphBuilderTest
{
    // Test task definition for creating steps
    @Serializable
    private data class TestTask(
        override val id: UUID = UUID.randomUUID(),
        override val name: String,
        override val description: String? = null
    ) : TaskDefinition

    @Test
    fun `build with empty steps returns empty graph`()
    {
        val builder = DependencyGraphBuilder()

        val result = builder.build( emptyList() )

        assertTrue( result.adjacency.isEmpty() )
        assertTrue( result.indegree.isEmpty() )
        assertTrue( result.issues.isEmpty() )
    }

    @Test
    fun `build with single step no dependencies`()
    {
        val builder = DependencyGraphBuilder()
        val step = createStep( "step1", emptyList(), listOf( "output1" ) )

        val result = builder.build( listOf( step ) )

        assertEquals( 1, result.adjacency.size )
        assertEquals( emptySet(), result.adjacency[step.metadata.id] )
        assertEquals( 0, result.indegree[step.metadata.id] )
        assertTrue( result.issues.isEmpty() )
    }

    @Test
    fun `build with two steps simple dependency`()
    {
        val builder = DependencyGraphBuilder()
        val step1 = createStepWithDescriptorId( "step1", "descriptor-step1", emptyList(), listOf( "output1" ) )
        val step2 = createStepWithDescriptorId(
            "step2",
            "descriptor-step2",
            listOf( createStepOutputInput( "output1", "descriptor-step1" ) ),
            listOf( "output2" )
        )

        val result = builder.build( listOf( step1, step2 ) )

        // step1 -> step2 dependency
        assertEquals( setOf( step2.metadata.id ), result.adjacency[step1.metadata.id] )
        assertEquals( emptySet(), result.adjacency[step2.metadata.id] )
        assertEquals( 0, result.indegree[step1.metadata.id] ) // no dependencies
        assertEquals( 1, result.indegree[step2.metadata.id] ) // depends on step1
        assertTrue( result.issues.isEmpty() )
    }

    @Test
    fun `build with complex dependency chain`()
    {
        val builder = DependencyGraphBuilder()
        val step1 = createStepWithDescriptorId( "step1", "descriptor-step1", emptyList(), listOf( "output1" ) )

        val step2 = createStepWithDescriptorId(
            "step2",
            "descriptor-step2",
            listOf( createStepOutputInput( "output1", "descriptor-step1") ),
            listOf( "output2" )
        )

        val step3 = createStepWithDescriptorId(
            "step3",
            "descriptor-step3",
            listOf( createStepOutputInput( "output2", "descriptor-step2") ),
            listOf( "output3" )
        )

        val step4 = createStepWithDescriptorId(
            "step4",
            "descriptor-step4",
            listOf(
                createStepOutputInput( "output1", "descriptor-step1"),
                createStepOutputInput( "output3", "descriptor-step3")
            ),
            emptyList()
        )

        val result = builder.build( listOf( step1, step2, step3, step4 ) )

        // Verify the dependency graph structure
        assertEquals( 4, result.adjacency.size )

        // step1 -> step2, step4
        assertEquals(result.adjacency[step1.metadata.id]?.contains( step2.metadata.id ), true)
        assertEquals(result.adjacency[step1.metadata.id]?.contains( step4.metadata.id ), true)

        // step2 -> step3
        assertEquals( setOf( step3.metadata.id ), result.adjacency[step2.metadata.id] )

        // step3 -> step4
        assertEquals( setOf( step4.metadata.id ), result.adjacency[step3.metadata.id] )

        // Verify indegrees
        assertEquals( 0, result.indegree[step1.metadata.id] )
        assertEquals( 1, result.indegree[step2.metadata.id] )
        assertEquals( 1, result.indegree[step3.metadata.id] )
        assertEquals( 2, result.indegree[step4.metadata.id] )

        assertTrue( result.issues.isEmpty() )
    }

    @Test
    fun `build detects missing producer step`()
    {
        val builder = DependencyGraphBuilder()

        val step = createStep(
            "step1",
            listOf( createStepOutputInput( "input1", "None") ),
            emptyList()
        )

        val result = builder.build( listOf( step ) )

        assertEquals( 1, result.issues.size )
        assertEquals( "DEPENDENCY_PRODUCER_NOT_FOUND", result.issues[0].code )
    }

    @Test
    fun `build detects missing producer output`()
    {
        val builder = DependencyGraphBuilder()
        val step1 = createStepWithDescriptorId( "step1", "descriptor-step1", emptyList(), listOf( "output1" ) )

        val step2 = createStepWithDescriptorId(
            "step2",
            "descriptor-step2",
            listOf( createStepOutputInput( "non-existent-output", "descriptor-step1") ),
            emptyList()
        )

        val result = builder.build( listOf( step1, step2 ) )

        assertEquals( 1, result.issues.size )
        assertEquals( "DEPENDENCY_OUTPUT_NOT_FOUND", result.issues[0].code )
    }

    @Test
    fun `build detects self-referencing dependency`()
    {
        val builder = DependencyGraphBuilder()
        val selfDepStepId = UUID.randomUUID()
        val selfDepOutputId = UUID.randomUUID()

        val selfDepStep = Step(
            metadata = StepMetadata(
                id = selfDepStepId,
                name = "step-with-self-dep",
                description = "Test step with self-dependency",
                version = Version( 1, 0 )
            ),
            inputs = listOf(
                InputDataSpec(
                    id = UUID.randomUUID(),
                    name = "Output self",
                    location = FileLocation( path = "", format = FileFormat.CSV ),
                    stepRef = selfDepStepId.toString(), // ← Self-reference
                    required = true
                )
            ),
            outputs = listOf(
                OutputDataSpec(
                    id = selfDepOutputId,
                    name = "Output self",
                    location = FileLocation( path = "", format = FileFormat.CSV )
                )
            ),
            task = TestTask( name = "test-task" ),
            environmentId = UUID.randomUUID()
        )

        val result = builder.build( listOf( selfDepStep ) )

        assertEquals( 1, result.issues.size )
        assertEquals( "DEPENDENCY_SELF_REFERENCE", result.issues[0].code )
    }

    @Test
    fun `build handles multiple validation errors`()
    {
        val builder = DependencyGraphBuilder()

        val step1 = createStep( "step1", emptyList(), listOf( "output1" ) )

        // Create step with self-dependency
        val selfDepStepId = UUID.randomUUID()
        val selfDepOutputId = UUID.randomUUID()
        val selfDepStep = Step(
            metadata = StepMetadata(
                id = selfDepStepId,
                name = "step-with-self-dep",
                description = "Test step with self-dependency",
                version = Version( 1, 0 )
            ),
            inputs = listOf(
                createStepOutputInput( "input1", "missingStepId"), // missing producer
                InputDataSpec(
                    id = UUID.randomUUID(),
                    name = "Input self-dep",
                    location = FileLocation( path = "", format = FileFormat.CSV ),
                    stepRef = selfDepStepId.toString(), // ← Self-dependency
                    required = true
                )
            ),
            outputs = listOf(
                OutputDataSpec(
                    id = selfDepOutputId,
                    name = "Output test",
                    location = FileLocation( path = "", format = FileFormat.CSV )
                )
            ),
            task = TestTask( name = "test-task" ),
            environmentId = UUID.randomUUID()
        )

        val step2 = createStep(
            "step2",
            listOf( createStepOutputInput( "input3", step1.metadata.name) ), // missing output
            emptyList()
        )

        val result = builder.build( listOf( selfDepStep, step1, step2 ) )

        assertTrue( result.issues.size >= 3 )
        val codes = result.issues.map { it.code }
        assertTrue( codes.contains( "DEPENDENCY_PRODUCER_NOT_FOUND" ) )
        assertTrue( codes.contains( "DEPENDENCY_SELF_REFERENCE" ) )
        assertTrue( codes.contains( "DEPENDENCY_OUTPUT_NOT_FOUND" ) )
    }

    @Test
    fun `build with diamond dependency pattern`()
    {
        val builder = DependencyGraphBuilder()
        //     step1
        //    /     \
        // step2   step3
        //    \     /
        //     step4
        val step1 = createStepWithDescriptorId( "step1", "descriptor-step1", emptyList(), listOf( "output1" ) )

        val step2 = createStepWithDescriptorId(
            "step2",
            "descriptor-step2",
            listOf( createStepOutputInput( "output1", "descriptor-step1") ),
            listOf( "output2" )
        )

        val step3 = createStepWithDescriptorId(
            "step3",
            "descriptor-step3",
            listOf( createStepOutputInput( "output1", "descriptor-step1") ),
            listOf( "output3" )
        )

        val step4 = createStepWithDescriptorId(
            "step4",
            "descriptor-step4",
            listOf(
                createStepOutputInput( "output2", "descriptor-step2"),
                createStepOutputInput( "output3", "descriptor-step3")
            ),
            emptyList()
        )

        val result = builder.build( listOf( step1, step2, step3, step4 ) )

        // Verify diamond structure
        assertEquals( 4, result.adjacency.size )
        assertEquals( setOf( step2.metadata.id, step3.metadata.id ), result.adjacency[step1.metadata.id] )
        assertEquals( setOf( step4.metadata.id ), result.adjacency[step2.metadata.id] )
        assertEquals( setOf( step4.metadata.id ), result.adjacency[step3.metadata.id] )

        // Verify indegrees
        assertEquals( 0, result.indegree[step1.metadata.id] )
        assertEquals( 1, result.indegree[step2.metadata.id] )
        assertEquals( 1, result.indegree[step3.metadata.id] )
        assertEquals( 2, result.indegree[step4.metadata.id] )

        assertTrue( result.issues.isEmpty() )
    }

    @Test
    fun `build preserves step order for deterministic output`()
    {
        val builder = DependencyGraphBuilder()
        val step1 = createStepWithDescriptorId( "step1", "descriptor-step1", emptyList(), listOf( "output1" ) )
        val step2 = createStepWithDescriptorId(
            "step2",
            "descriptor-step2",
            listOf( createStepOutputInput( "output1", "descriptor-step1") ),
            listOf( "output2" )
        )
        val step3 = createStepWithDescriptorId(
            "step3",
            "descriptor-step3",
            listOf( createStepOutputInput( "output2", "descriptor-step2") ),
            listOf( "output3" )
        )

        val result = builder.build( listOf( step1, step2, step3 ) )

        assertTrue( result.issues.isEmpty() )
        assertEquals( 3, result.adjacency.size )
    }

    // ── Helper Functions ──────────────────────────────────────────────────────

    private fun createStep(
        name: String,
        inputs: List<InputDataSpec> = emptyList(),
        outputNames: List<String> = emptyList()
    ): Step
    {
        val outputs = outputNames.map { outputName ->
            OutputDataSpec(
                id = UUID.randomUUID(),
                name = "Output $outputName",
                location = FileLocation( path = "", format = FileFormat.CSV )
            )
        }

        return Step(
            metadata = StepMetadata(
                id = UUID.randomUUID(),
                name = name,
                description = "Test step: $name",
                version = Version( 1, 0 )
            ),
            inputs = inputs,
            outputs = outputs,
            task = TestTask( name = "task-$name" ),
            environmentId = UUID.randomUUID()
        )
    }

    private fun createStepWithDescriptorId(
        name: String,
        descriptorId: String,
        inputs: List<InputDataSpec> = emptyList(),
        outputNames: List<String> = emptyList()
    ): Step
    {
        val outputs = outputNames.map { outputName ->
            OutputDataSpec(
                id = UUID.randomUUID(),
                name = "Output $outputName",
                location = FileLocation( path = "", format = FileFormat.CSV )
            )
        }

        return Step(
            metadata = StepMetadata(
                id = UUID.randomUUID(),
                name = name,
                description = "Test step: $name",
                version = Version( 1, 0 ),
                descriptorId = descriptorId
            ),
            inputs = inputs,
            outputs = outputs,
            task = TestTask( name = "task-$name" ),
            environmentId = UUID.randomUUID()
        )
    }

    private fun createStepOutputInput(
        identifier: String,
        stepName: String,
        inputDataSpecId: UUID? = null
    ): InputDataSpec
    {
        return InputDataSpec(
            id = inputDataSpecId ?: UUID.randomUUID(),
            name = "Output $identifier",
            location = FileLocation( path = "", format = FileFormat.CSV ),
            stepRef = stepName,
            required = true
        )
    }
}
