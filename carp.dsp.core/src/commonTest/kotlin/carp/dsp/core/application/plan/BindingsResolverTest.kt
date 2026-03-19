package carp.dsp.core.application.plan

import dk.cachet.carp.analytics.application.plan.*
import dk.cachet.carp.analytics.domain.data.*
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
 * Tests cover:
 * - Output resolution using unified DataLocation model
 * - External input resolution (stepRef == null)
 * - Cross-step input resolution (stepRef != null)
 * - Error handling for missing producers/outputs
 * - Deterministic location resolution
 */
class BindingsResolverTest
{
    private val resolver = BindingsResolver()
    private val executionIndex = 1

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
    ): Step
    {
        return Step(
            metadata = StepMetadata(
                id = UUID.randomUUID(),
                name = name,
                description = "Test step: $name",
                version = Version( 1, 0 )
            ),
            task = MockTaskDefinition( name = "task-$name" ),
            environmentId = UUID.randomUUID(),
            inputs = inputs,
            outputs = outputs
        )
    }

    private fun createOutputSpec(
        name: String,
        location: DataLocation = InMemoryLocation( registryKey = "output-$name" )
    ): OutputDataSpec
    {
        return OutputDataSpec(
            id = UUID.randomUUID(),
            name = name,
            description = "Output $name",
            location = location
        )
    }

    private fun createInputSpec(
        name: String,
        location: DataLocation = FileLocation( path = "/data/$name", format = FileFormat.CSV ),
        stepRef: String? = null
    ): InputDataSpec
    {
        return InputDataSpec(
            id = UUID.randomUUID(),
            name = name,
            description = "Input $name",
            schema = null,
            location = location,
            stepRef = stepRef,
            required = true,
            constraints = null
        )
    }

    private fun createPlannedStep(
        metadata: StepMetadata,
        bindings: ResolvedBindings
    ): PlannedStep
    {
        return PlannedStep(
            metadata = metadata,
            process = CommandSpec( "echo", listOf( ExpandedArg.Literal( "test" ) ) ),
            bindings = bindings,
            environmentRef = UUID.randomUUID()
        )
    }

    // ── Output Resolution Tests ───────────────────────────────────────────────

    @Test
    fun `resolve creates outputs from step's OutputDataSpecs`()
    {
        // Arrange
        val location1 = InMemoryLocation( registryKey = "out1" )
        val location2 = FileLocation( path = "/outputs/out2.csv", format = FileFormat.CSV )
        val output1 = createOutputSpec( "output1", location1 )
        val output2 = createOutputSpec( "output2", location2 )
        val step = createStep( "test-step", outputs = listOf( output1, output2 ) )
        val issues = mutableListOf<PlanIssue>()

        // Act
        val bindings = resolver.resolve( step, emptyMap(), issues, executionIndex )

        // Assert
        assertTrue( issues.isEmpty() )
        assertEquals( 2, bindings.outputs.size )

        val resolved1 = bindings.outputs[output1.id]
        assertNotNull( resolved1 )
        assertEquals( output1.id, resolved1.spec.id )
        assertEquals( "output1", resolved1.spec.name )

        val resolved2 = bindings.outputs[output2.id]
        assertNotNull( resolved2 )
        assertEquals( output2.id, resolved2.spec.id )
        assertEquals( "output2", resolved2.spec.name )
    }

    @Test
    fun `resolve handles step with no outputs`()
    {
        // Arrange
        val step = createStep( "no-output-step", outputs = emptyList() )
        val issues = mutableListOf<PlanIssue>()

        // Act
        val bindings = resolver.resolve( step, emptyMap(), issues, executionIndex )

        // Assert
        assertTrue( issues.isEmpty() )
        assertTrue( bindings.outputs.isEmpty() )
    }

    @Test
    fun `resolve handles outputs with InMemoryLocation`()
    {
        // Arrange
        val registryLocation = InMemoryLocation( registryKey = "my-registry-key" )
        val output = createOutputSpec( "in-memory-output", registryLocation )
        val step = createStep( "test-step", outputs = listOf( output ) )
        val issues = mutableListOf<PlanIssue>()

        // Act
        val bindings = resolver.resolve( step, emptyMap(), issues, executionIndex )

        // Assert
        assertTrue( issues.isEmpty() )
        val resolved = bindings.outputs[output.id]
        assertNotNull( resolved )
        assertTrue( resolved.location is InMemoryLocation )
    }

    @Test
    fun `resolve handles outputs with FileLocation`()
    {
        // Arrange
        val fileLocation = FileLocation( path = "/outputs/result.csv", format = FileFormat.CSV )
        val output = createOutputSpec( "file-output", fileLocation )
        val step = createStep( "test-step", outputs = listOf( output ) )
        val issues = mutableListOf<PlanIssue>()

        // Act
        val bindings = resolver.resolve( step, emptyMap(), issues, executionIndex )

        // Assert
        assertTrue( issues.isEmpty() )
        val resolved = bindings.outputs[output.id]
        assertNotNull( resolved )
        assertTrue( resolved.location is FileLocation )
    }

    // ── External Input Resolution Tests ───────────────────────────────────────

    @Test
    fun `resolve handles external input with stepRef null`()
    {
        // Arrange
        val location = FileLocation( path = "/data/input.csv", format = FileFormat.CSV )
        val input = createInputSpec( "external-input", location, stepRef = null )
        val step = createStep( "test-step", inputs = listOf( input ) )
        val issues = mutableListOf<PlanIssue>()

        // Act
        val bindings = resolver.resolve( step, emptyMap(), issues, executionIndex )

        // Assert
        assertTrue( issues.isEmpty() )
        assertEquals( 1, bindings.inputs.size )

        val resolved = bindings.inputs[input.id]
        assertNotNull( resolved )
        assertEquals( input.id, resolved.spec.id )
        assertTrue( resolved.location is FileLocation )
    }

    @Test
    fun `resolve handles multiple external inputs`()
    {
        // Arrange
        val input1 = createInputSpec( "input1", FileLocation( path = "/data/a.csv" ), stepRef = null )
        val input2 = createInputSpec( "input2", InMemoryLocation( registryKey = "input2" ), stepRef = null )
        val step = createStep( "test-step", inputs = listOf( input1, input2 ) )
        val issues = mutableListOf<PlanIssue>()

        // Act
        val bindings = resolver.resolve( step, emptyMap(), issues, executionIndex )

        // Assert
        assertTrue( issues.isEmpty() )
        assertEquals( 2, bindings.inputs.size )

        val resolved1 = bindings.inputs[input1.id]
        assertNotNull( resolved1 )
        assertEquals( "input1", resolved1.spec.name )

        val resolved2 = bindings.inputs[input2.id]
        assertNotNull( resolved2 )
        assertEquals( "input2", resolved2.spec.name )
    }

    // ── Cross-Step Input Resolution Tests ─────────────────────────────────────

    @Test
    fun `resolve handles cross-step input with stepRef non-null`()
    {
        // Arrange
        val producerMetadata = StepMetadata(
            id = UUID.randomUUID(),
            name = "producer",
            version = Version( 1, 0 )
        )
        val producerOutput = createOutputSpec( "shared-output", InMemoryLocation( registryKey = "shared" ) )
        val producerBindings = ResolvedBindings(
            inputs = emptyMap(),
            outputs = mapOf( producerOutput.id to ResolvedOutput( producerOutput, producerOutput.location ) )
        )
        val plannedProducer = createPlannedStep( producerMetadata, producerBindings )

        val consumerInput = createInputSpec( "shared-output", stepRef = "producer" )
        val consumerStep = createStep( "consumer", inputs = listOf( consumerInput ) )
        val issues = mutableListOf<PlanIssue>()

        // Act
        val bindings = resolver.resolve(
            consumerStep,
            mapOf( producerMetadata.id to plannedProducer ),
            issues,
            executionIndex
        )

        // Assert
        assertTrue( issues.isEmpty() )
        assertEquals( 1, bindings.inputs.size )

        val resolved = bindings.inputs[consumerInput.id]
        assertNotNull( resolved )
        assertEquals( consumerInput.id, resolved.spec.id )
        // Location should come from producer's output
        assertTrue( resolved.location is InMemoryLocation )
    }

    @Test
    fun `resolve emits ERROR for missing producer step`()
    {
        // Arrange
        val consumerInput = createInputSpec(
            "missing-output",
            FileLocation( path = "/data/placeholder.csv" ),
            stepRef = "non-existent-producer"
        )
        val consumerStep = createStep( "consumer", inputs = listOf( consumerInput ) )
        val issues = mutableListOf<PlanIssue>()

        // Act
        val bindings = resolver.resolve( consumerStep, emptyMap(), issues, executionIndex )

        // Assert
        assertEquals( 1, issues.size )
        val issue = issues[0]
        assertEquals( PlanIssueSeverity.ERROR, issue.severity )
        assertEquals( "MISSING_PRODUCER_STEP", issue.code )
        assertEquals( consumerStep.metadata.id, issue.stepId )
        assertTrue( issue.message.contains( "non-existent-producer" ) )

        // Input still included in bindings (with fallback location)
        assertEquals( 1, bindings.inputs.size )
    }

    @Test
    fun `resolve emits ERROR for missing producer output`()
    {
        // Arrange
        val producerMetadata = StepMetadata(
            id = UUID.randomUUID(),
            name = "producer",
            version = Version( 1, 0 )
        )
        val producerOutput = createOutputSpec( "available-output" )
        val producerBindings = ResolvedBindings(
            inputs = emptyMap(),
            outputs = mapOf( producerOutput.id to ResolvedOutput( producerOutput, producerOutput.location ) )
        )
        val plannedProducer = createPlannedStep( producerMetadata, producerBindings )

        // Consumer wants "missing-output" but producer only has "available-output"
        val consumerInput = createInputSpec(
            "missing-output",
            FileLocation( path = "/data/placeholder.csv" ),
            stepRef = "producer"
        )
        val consumerStep = createStep( "consumer", inputs = listOf( consumerInput ) )
        val issues = mutableListOf<PlanIssue>()

        // Act
        val bindings = resolver.resolve(
            consumerStep,
            mapOf( producerMetadata.id to plannedProducer ),
            issues,
            executionIndex
        )

        // Assert
        assertEquals( 1, issues.size )
        val issue = issues[0]
        assertEquals( PlanIssueSeverity.ERROR, issue.severity )
        assertEquals( "MISSING_PRODUCER_OUTPUT", issue.code )
        assertEquals( consumerStep.metadata.id, issue.stepId )
        assertTrue( issue.message.contains( "missing-output" ) )

        // Input still included in bindings (with fallback location)
        assertEquals( 1, bindings.inputs.size )
    }

    @Test
    fun `resolve handles multiple inputs from same producer`()
    {
        // Arrange
        val producerMetadata = StepMetadata(
            id = UUID.randomUUID(),
            name = "multi-output-producer",
            version = Version( 1, 0 )
        )
        val output1 = createOutputSpec( "output1", InMemoryLocation( registryKey = "out1" ) )
        val output2 = createOutputSpec( "output2", FileLocation( path = "/out2.csv" ) )
        val producerBindings = ResolvedBindings(
            inputs = emptyMap(),
            outputs = mapOf(
                output1.id to ResolvedOutput( output1, output1.location ),
                output2.id to ResolvedOutput( output2, output2.location )
            )
        )
        val plannedProducer = createPlannedStep( producerMetadata, producerBindings )

        val input1 = createInputSpec( "output1", stepRef = "multi-output-producer" )
        val input2 = createInputSpec( "output2", stepRef = "multi-output-producer" )
        val consumerStep = createStep( "consumer", inputs = listOf( input1, input2 ) )
        val issues = mutableListOf<PlanIssue>()

        // Act
        val bindings = resolver.resolve(
            consumerStep,
            mapOf( producerMetadata.id to plannedProducer ),
            issues,
            executionIndex
        )

        // Assert
        assertTrue( issues.isEmpty() )
        assertEquals( 2, bindings.inputs.size )

        val resolved1 = bindings.inputs[input1.id]
        assertNotNull( resolved1 )
        assertTrue( resolved1.location is InMemoryLocation )

        val resolved2 = bindings.inputs[input2.id]
        assertNotNull( resolved2 )
        assertTrue( resolved2.location is FileLocation )
    }

    // ── Mixed Input Resolution Tests ──────────────────────────────────────────

    @Test
    fun `resolve handles mix of external and cross-step inputs`()
    {
        // Arrange
        val producerMetadata = StepMetadata(
            id = UUID.randomUUID(),
            name = "producer",
            version = Version( 1, 0 )
        )
        val producerOutput = createOutputSpec( "produced-output" )
        val producerBindings = ResolvedBindings(
            inputs = emptyMap(),
            outputs = mapOf( producerOutput.id to ResolvedOutput( producerOutput, producerOutput.location ) )
        )
        val plannedProducer = createPlannedStep( producerMetadata, producerBindings )

        val externalInput = createInputSpec( "external-data", stepRef = null )
        val crossStepInput = createInputSpec( "produced-output", stepRef = "producer" )
        val consumerStep = createStep( "consumer", inputs = listOf( externalInput, crossStepInput ) )
        val issues = mutableListOf<PlanIssue>()

        // Act
        val bindings = resolver.resolve(
            consumerStep,
            mapOf( producerMetadata.id to plannedProducer ),
            issues,
            executionIndex
        )

        // Assert
        assertTrue( issues.isEmpty() )
        assertEquals( 2, bindings.inputs.size )

        val resolvedExternal = bindings.inputs[externalInput.id]
        assertNotNull( resolvedExternal )
        assertEquals( "external-data", resolvedExternal.spec.name )

        val resolvedCrossStep = bindings.inputs[crossStepInput.id]
        assertNotNull( resolvedCrossStep )
        assertEquals( "produced-output", resolvedCrossStep.spec.name )
    }

    @Test
    fun `resolve handles step with no inputs or outputs`()
    {
        // Arrange
        val step = createStep( "empty-step", inputs = emptyList(), outputs = emptyList() )
        val issues = mutableListOf<PlanIssue>()

        // Act
        val bindings = resolver.resolve( step, emptyMap(), issues, executionIndex )

        // Assert
        assertTrue( issues.isEmpty() )
        assertTrue( bindings.inputs.isEmpty() )
        assertTrue( bindings.outputs.isEmpty() )
    }

    // ── Output Location Resolution Tests ───────────────────────────────────────

    @Test
    fun `resolve generates workspace-relative path for blank FileLocation output`()
    {
        val output = createOutputSpec(
            "features",
            FileLocation( path = "", format = FileFormat.CSV )
        )
        val step = createStep( "Extract Features", outputs = listOf( output ) )
        val issues = mutableListOf<PlanIssue>()

        val bindings = resolver.resolve( step, emptyMap(), issues, executionIndex = 1 )

        assertTrue( issues.isEmpty() )
        val location = bindings.outputs[output.id]!!.location as FileLocation
        assertEquals( "steps/02_extract_features/outputs/features.csv", location.path )
    }

    @Test
    fun `resolve places relative FileLocation output under step outputs directory`()
    {
        val output = createOutputSpec(
            "result",
            FileLocation( path = "processed/result.json", format = FileFormat.JSON )
        )
        val step = createStep( "Process Data", outputs = listOf( output ) )
        val issues = mutableListOf<PlanIssue>()

        val bindings = resolver.resolve( step, emptyMap(), issues, executionIndex = 0 )

        assertTrue( issues.isEmpty() )
        val location = bindings.outputs[output.id]!!.location as FileLocation
        assertEquals( "steps/01_process_data/outputs/processed/result.json", location.path )
    }

    @Test
    fun `resolve passes through absolute FileLocation output path unchanged`()
    {
        val output = createOutputSpec(
            "archive",
            FileLocation( path = "/data/archive/result.parquet", format = FileFormat.PARQUET )
        )
        val step = createStep( "Archive Step", outputs = listOf( output ) )
        val issues = mutableListOf<PlanIssue>()

        val bindings = resolver.resolve( step, emptyMap(), issues, executionIndex = 2 )

        assertTrue( issues.isEmpty() )
        val location = bindings.outputs[output.id]!!.location as FileLocation
        assertEquals( "/data/archive/result.parquet", location.path )
    }

    @Test
    fun `resolve uses executionIndex to determine step directory name`()
    {
        val output = createOutputSpec( "out", FileLocation( path = "", format = FileFormat.CSV ) )
        val step = createStep( "My Step", outputs = listOf( output ) )
        val issues = mutableListOf<PlanIssue>()

        val bindingsAt0 = resolver.resolve( step, emptyMap(), issues, executionIndex = 0 )
        val bindingsAt4 = resolver.resolve( step, emptyMap(), issues, executionIndex = 4 )

        val pathAt0 = (bindingsAt0.outputs[output.id]!!.location as FileLocation).path
        val pathAt4 = (bindingsAt4.outputs[output.id]!!.location as FileLocation).path

        assertTrue( pathAt0.startsWith( "steps/01_" ) )
        assertTrue( pathAt4.startsWith( "steps/05_" ) )
    }

    // ── External and cross-step input passthrough Tests ──────────────────────────────

    @Test
    fun `resolve passes external input location through with exact path value unchanged`()
    {
        val location = FileLocation( path = "/data/raw_eeg.tsv", format = FileFormat.TSV )
        val input = createInputSpec( "raw-eeg", location, stepRef = null )
        val step = createStep( "Validate Input", inputs = listOf( input ) )
        val issues = mutableListOf<PlanIssue>()

        val bindings = resolver.resolve( step, emptyMap(), issues, executionIndex = 0 )

        assertTrue( issues.isEmpty() )
        assertEquals( location, bindings.inputs[input.id]!!.location )
    }

    @Test
    fun `resolve copies exact location object from producer output for cross-step input`()
    {
        val producerLocation = FileLocation(
            path = "steps/01_producer/outputs/result.csv",
            format = FileFormat.CSV
        )
        val producerOutput = createOutputSpec( "result", producerLocation )
        val producerBindings = ResolvedBindings(
            inputs = emptyMap(),
            outputs = mapOf( producerOutput.id to ResolvedOutput( producerOutput, producerLocation ) )
        )
        val plannedProducer = createPlannedStep(
            StepMetadata( id = UUID.randomUUID(), name = "producer", version = Version( 1, 0 ) ),
            producerBindings
        )

        val consumerInput = createInputSpec( "result", stepRef = "producer" )
        val consumerStep = createStep( "consumer", inputs = listOf( consumerInput ) )
        val issues = mutableListOf<PlanIssue>()

        val bindings = resolver.resolve(
            consumerStep,
            mapOf( plannedProducer.metadata.id to plannedProducer ),
            issues,
            executionIndex = 1
        )

        assertTrue( issues.isEmpty() )
        assertEquals( producerLocation, bindings.inputs[consumerInput.id]!!.location )
    }

    // ── System-specific path warnings ───────────────────────────────────────

    @Test
    fun `resolve emits WARNING for Windows drive letter path`()
    {
        val input = createInputSpec(
            "windows-data",
            FileLocation( path = "C:\\Users\\nigel\\data\\raw.csv", format = FileFormat.CSV ),
            stepRef = null
        )
        val step = createStep( "Load Data", inputs = listOf( input ) )
        val issues = mutableListOf<PlanIssue>()

        resolver.resolve( step, emptyMap(), issues, executionIndex = 0 )

        assertEquals( 1, issues.size )
        assertEquals( PlanIssueSeverity.WARNING, issues[0].severity )
        assertEquals( "SYSTEM_SPECIFIC_PATH", issues[0].code )
    }

    @Test
    fun `resolve emits WARNING for UNC network path`()
    {
        val input = createInputSpec(
            "network-data",
            FileLocation( path = "\\\\server\\share\\data.csv", format = FileFormat.CSV ),
            stepRef = null
        )
        val step = createStep( "Load Data", inputs = listOf( input ) )
        val issues = mutableListOf<PlanIssue>()

        resolver.resolve( step, emptyMap(), issues, executionIndex = 0 )

        assertEquals( 1, issues.size )
        assertEquals( PlanIssueSeverity.WARNING, issues[0].severity )
        assertEquals( "SYSTEM_SPECIFIC_PATH", issues[0].code )
    }

    @Test
    fun `resolve emits WARNING for Unix user home directory path`()
    {
        listOf( "/home/nigel/data.csv", "/Users/nigel/data.csv" ).forEach { path ->
            val input = createInputSpec(
                "home-data",
                FileLocation( path = path, format = FileFormat.CSV ),
                stepRef = null
            )
            val step = createStep( "Load Data", inputs = listOf( input ) )
            val issues = mutableListOf<PlanIssue>()

            resolver.resolve( step, emptyMap(), issues, executionIndex = 0 )

            assertEquals( 1, issues.size, "Expected warning for path '$path'" )
            assertEquals( PlanIssueSeverity.WARNING, issues[0].severity )
            assertEquals( "SYSTEM_SPECIFIC_PATH", issues[0].code )
        }
    }

    @Test
    fun `resolve does not emit warning for generic absolute path`()
    {
        val input = createInputSpec(
            "shared-data",
            FileLocation( path = "/data/shared/eeg_study.csv", format = FileFormat.CSV ),
            stepRef = null
        )
        val step = createStep( "Load Data", inputs = listOf( input ) )
        val issues = mutableListOf<PlanIssue>()

        resolver.resolve( step, emptyMap(), issues, executionIndex = 0 )

        assertTrue( issues.none { it.code == "SYSTEM_SPECIFIC_PATH" } )
    }

    @Test
    fun `resolve does not emit warning for relative or blank paths`()
    {
        listOf( "", "data/raw.csv", "./data/raw.csv" ).forEach { path ->
            val input = createInputSpec(
                "relative-data",
                FileLocation( path = path, format = FileFormat.CSV ),
                stepRef = null
            )
            val step = createStep( "Load Data", inputs = listOf( input ) )
            val issues = mutableListOf<PlanIssue>()

            resolver.resolve( step, emptyMap(), issues, executionIndex = 0 )

            assertTrue(
                issues.none { it.code == "SYSTEM_SPECIFIC_PATH" },
                "Path '$path' should not trigger a warning"
            )
        }
    }
}
