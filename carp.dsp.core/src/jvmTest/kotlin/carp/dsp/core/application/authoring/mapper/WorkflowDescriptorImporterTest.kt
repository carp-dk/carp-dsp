package carp.dsp.core.application.authoring.mapper

import carp.dsp.core.application.authoring.descriptor.CommandTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.DataDescriptor
import carp.dsp.core.application.authoring.descriptor.DataPortDescriptor
import carp.dsp.core.application.authoring.descriptor.EnvironmentDescriptor
import carp.dsp.core.application.authoring.descriptor.PythonTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.ScriptEntryPointDescriptor
import carp.dsp.core.application.authoring.descriptor.StepDescriptor
import carp.dsp.core.application.authoring.descriptor.StepMetadataDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowMetadataDescriptor
import carp.dsp.core.application.environment.CondaEnvironmentDefinition
import dk.cachet.carp.analytics.domain.tasks.CommandTaskDefinition
import dk.cachet.carp.analytics.domain.tasks.PythonTaskDefinition
import dk.cachet.carp.analytics.domain.workflow.Step
import dk.cachet.carp.analytics.domain.workflow.Version
import dk.cachet.carp.common.application.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for [WorkflowDescriptorImporter].
 */
class WorkflowDescriptorImporterTest
{
    private val namespace = UUID( "6ba7b810-9dad-11d1-80b4-00c04fd430c8" )
    private val importer = WorkflowDescriptorImporter( namespace )
    private val exporter = WorkflowDescriptorExporter()

    // ── Helpers

    private fun minimalDesc(
        wfId: String? = UUID.randomUUID().toString(),
        steps: List<StepDescriptor> = emptyList(),
        environments: Map<String, EnvironmentDescriptor> = emptyMap(),
    ) = WorkflowDescriptor(
        schemaVersion = "1.0",
        metadata = WorkflowMetadataDescriptor( id = wfId, name = "Test WF" ),
        steps = steps,
        environments = environments,
    )

    private fun condaEnvDesc( name: String = "env" ) = EnvironmentDescriptor(
        name = name, kind = "conda",
        spec = mapOf(
            "dependencies" to listOf("numpy"),
            "pythonVersion" to listOf("3.11"),
            "channels" to listOf("conda-forge"),
        )
    )

    private fun cmdStep(
        id: String? = UUID.randomUUID().toString(),
        envId: String = UUID.randomUUID().toString(),
    ) = StepDescriptor(
        id = id,
        environmentId = envId,
        task = CommandTaskDescriptor( name = "t", executable = "echo" ),
    )

    // ── Workflow metadata

    @Test
    fun `import preserves workflow metadata id name description version`()
    {
        val wfId = UUID.randomUUID()
        val desc = WorkflowDescriptor(
            schemaVersion = "1.0",
            metadata = WorkflowMetadataDescriptor(
                id = wfId.toString(),
                name = "My Workflow",
                description = "A test workflow",
                version = "2.1",
            )
        )

        val meta = importer.import( desc ).workflow.metadata
        assertEquals( wfId, meta.id )
        assertEquals( "My Workflow", meta.name )
        assertEquals( "A test workflow", meta.description )
        assertEquals( Version(2, 1), meta.version )
    }

    @Test
    fun `import generates deterministic workflow id when metadata id is null`()
    {
        val desc = minimalDesc( wfId = null )

        val first = importer.import( desc ).workflow.metadata.id
        val second = importer.import( desc ).workflow.metadata.id
        assertNotNull( first )
        assertEquals( first, second, "Generated workflow id must be deterministic" )
    }

    @Test
    fun `import generated workflow id differs across namespaces`()
    {
        val otherNamespace = UUID( "6ba7b811-9dad-11d1-80b4-00c04fd430c8" )
        val desc = minimalDesc( wfId = null )

        val idA = WorkflowDescriptorImporter( namespace ).import( desc ).workflow.metadata.id
        val idB = WorkflowDescriptorImporter( otherNamespace ).import( desc ).workflow.metadata.id
        assertNotEquals( idA, idB )
    }

    // ── Step count and ordering ───────────────────────────────────────────────

    @Test
    fun `import produces correct step count`()
    {
        val envId = UUID.randomUUID().toString()
        val desc = minimalDesc(
            steps = listOf( cmdStep( envId = envId ), cmdStep( envId = envId ) )
        )
        assertEquals( 2, importer.import( desc ).workflow.getComponents().size )
    }

    @Test
    fun `import preserves step order`()
    {
        val envId = UUID.randomUUID().toString()
        val id1 = UUID.randomUUID().toString()
        val id2 = UUID.randomUUID().toString()
        val desc = minimalDesc(
            steps = listOf( cmdStep( id = id1, envId = envId ), cmdStep( id = id2, envId = envId ) )
        )

        val steps = importer.import( desc ).workflow.getComponents().filterIsInstance<Step>()
        assertEquals( UUID.parse(id1), steps[0].metadata.id )
        assertEquals( UUID.parse(id2), steps[1].metadata.id )
    }

    // ── Step metadata ─────────────────────────────────────────────────────────

    @Test
    fun `import preserves step metadata name description version`()
    {
        val envId = UUID.randomUUID().toString()
        val desc = minimalDesc(
            steps = listOf(
                StepDescriptor(
                    id = UUID.randomUUID().toString(),
                    environmentId = envId,
                    metadata = StepMetadataDescriptor(
                        name = "Preprocess", description = "Does filtering", version = "3.2"
                    ),
                    task = CommandTaskDescriptor( name = "t", executable = "echo" ),
                )
            )
        )

        val step = importer.import( desc ).workflow.getComponents().first() as Step
        assertEquals( "Preprocess", step.metadata.name )
        assertEquals( "Does filtering", step.metadata.description )
        assertEquals( Version(3, 2), step.metadata.version )
    }

    @Test
    fun `import uses stepId toString as step name when metadata is absent`()
    {
        val stepId = UUID.randomUUID()
        val desc = minimalDesc( steps = listOf( cmdStep( id = stepId.toString() ) ) )

        val step = importer.import( desc ).workflow.getComponents().first() as Step
        assertEquals( stepId.toString(), step.metadata.name )
    }

    // ── Step ID: deterministic generation ─────────────────────────────────────

    @Test
    fun `import generates deterministic step id when step id is null`()
    {
        val desc = minimalDesc( steps = listOf( cmdStep( id = null ) ) )

        val id1 = ( importer.import( desc ).workflow.getComponents().first() as Step ).metadata.id
        val id2 = ( importer.import( desc ).workflow.getComponents().first() as Step ).metadata.id
        assertNotNull( id1 )
        assertEquals( id1, id2, "Generated step id must be deterministic" )
    }

    @Test
    fun `import generated step id differs across namespaces`()
    {
        val otherNamespace = UUID( "6ba7b811-9dad-11d1-80b4-00c04fd430c8" )
        val desc = minimalDesc( steps = listOf( cmdStep( id = null ) ) )

        val idA = (
            WorkflowDescriptorImporter( namespace ).import( desc )
            .workflow.getComponents().first() as Step
        ).metadata.id
        val idB = (
            WorkflowDescriptorImporter( otherNamespace ).import( desc )
            .workflow.getComponents().first() as Step
        ).metadata.id
        assertNotEquals( idA, idB )
    }

    // ── Task type dispatch

    @Test
    fun `import produces CommandTaskDefinition for command task`()
    {
        val desc = minimalDesc( steps = listOf( cmdStep() ) )
        val step = importer.import( desc ).workflow.getComponents().first() as Step
        assertIs<CommandTaskDefinition>( step.task )
    }

    @Test
    fun `import produces PythonTaskDefinition for python task`()
    {
        val envId = UUID.randomUUID().toString()
        val desc = minimalDesc(
            steps = listOf(
                StepDescriptor(
                    environmentId = envId,
                    task = PythonTaskDescriptor(
                        name = "py", entryPoint = ScriptEntryPointDescriptor( "run.py" )
                    ),
                )
            )
        )

        val step = importer.import( desc ).workflow.getComponents().first() as Step
        assertIs<PythonTaskDefinition>( step.task )
    }

    @Test
    fun `import preserves command task executable and args`()
    {
        val envId = UUID.randomUUID().toString()
        val desc = minimalDesc(
            steps = listOf(
                StepDescriptor(
                    environmentId = envId,
                    task = CommandTaskDescriptor(
                        name = "run", executable = "python",
                        args = listOf( "script.py" ),
                    ),
                )
            )
        )

        val task = assertIs<CommandTaskDefinition>(
            ( importer.import( desc ).workflow.getComponents().first() as Step ).task
        )
        assertEquals( "python", task.executable )
        assertEquals( 1, task.args.size )
    }

    // ── Environment resolution

    @Test
    fun `import builds environment map with correct size`()
    {
        val envId = UUID.randomUUID().toString()
        val desc = minimalDesc(
            environments = mapOf( envId to condaEnvDesc() )
        )
        assertEquals( 1, importer.import( desc ).environments.size )
    }

    @Test
    fun `import resolves UUID string environment key`()
    {
        val envId = UUID.randomUUID()
        val desc = minimalDesc(
            environments = mapOf( envId.toString() to condaEnvDesc() )
        )

        val definition = importer.import( desc )
        assertNotNull( definition.environments[envId] )
    }

    @Test
    fun `import resolves human-readable environment key deterministically`()
    {
        val desc = minimalDesc(
            environments = mapOf( "env-conda-001" to condaEnvDesc() )
        )

        val defA = WorkflowDescriptorImporter( namespace ).import( desc )
        val defB = WorkflowDescriptorImporter( namespace ).import( desc )

        // Same namespace → same generated UUID → same map key
        assertEquals( defA.environments.keys, defB.environments.keys )
    }

    @Test
    fun `import step environmentId matches the resolved environment UUID`()
    {
        val envKey = "env-conda-001"
        val desc = minimalDesc(
            steps = listOf( cmdStep( envId = envKey ) ),
            environments = mapOf( envKey to condaEnvDesc() ),
        )

        val definition = importer.import( desc )
        val stepEnvId = ( definition.workflow.getComponents().first() as Step ).environmentId
        // The step's environmentId must be a key in the environment map
        assertNotNull(
            definition.environments[stepEnvId],
            "Step environmentId $stepEnvId not found in environments map"
        )
    }

    @Test
    fun `import maps conda environment kind correctly`()
    {
        val envId = UUID.randomUUID().toString()
        val desc = minimalDesc(
            environments = mapOf( envId to condaEnvDesc( name = "base-env" ) )
        )

        val env = importer.import( desc ).environments.values.single()
        assertIs<CondaEnvironmentDefinition>( env )
        assertEquals( "base-env", env.name )
    }

    // ── Port preservation

    @Test
    fun `import preserves input port id`()
    {
        val portId = UUID.randomUUID()
        val desc = minimalDesc(
            steps = listOf(
                StepDescriptor(
                    environmentId = UUID.randomUUID().toString(),
                    task = CommandTaskDescriptor( name = "t", executable = "echo" ),
                    inputs = listOf( DataPortDescriptor( id = portId.toString() ) ),
                )
            )
        )

        val step = importer.import( desc ).workflow.getComponents().first() as Step
        assertEquals( portId, step.inputs.first().id )
    }

    @Test
    fun `import preserves output port id`()
    {
        val portId = UUID.randomUUID()
        val desc = minimalDesc(
            steps = listOf(
                StepDescriptor(
                    environmentId = UUID.randomUUID().toString(),
                    task = CommandTaskDescriptor( name = "t", executable = "echo" ),
                    outputs = listOf( DataPortDescriptor( id = portId.toString() ) ),
                )
            )
        )

        val step = importer.import( desc ).workflow.getComponents().first() as Step
        assertEquals( portId, step.outputs.first().id )
    }

    @Test
    fun `import derives DataSchema from port descriptor when present`()
    {
        val desc = minimalDesc(
            steps = listOf(
                StepDescriptor(
                    environmentId = UUID.randomUUID().toString(),
                    task = CommandTaskDescriptor( name = "t", executable = "echo" ),
                    outputs = listOf(
                        DataPortDescriptor(
                        id = UUID.randomUUID().toString(),
                        descriptor = DataDescriptor( type = "csv", format = "UTF-8" )
                    )
                    ),
                )
            )
        )

        val step = importer.import( desc ).workflow.getComponents().first() as Step
        assertNotNull( step.outputs.first().schema )
    }

    @Test
    fun `import produces null DataSchema when port descriptor is absent`()
    {
        val desc = minimalDesc(
            steps = listOf(
                StepDescriptor(
                    environmentId = UUID.randomUUID().toString(),
                    task = CommandTaskDescriptor( name = "t", executable = "echo" ),
                    inputs = listOf( DataPortDescriptor( id = UUID.randomUUID().toString() ) ),
                )
            )
        )

        val step = importer.import( desc ).workflow.getComponents().first() as Step
        assertNull( step.inputs.first().schema )
    }

    // ── Export → Import → Export symmetry

    @Test
    fun `export then import then export produces identical descriptor`()
    {
        val envId = UUID.randomUUID()
        val stepId = UUID.randomUUID()
        val taskId = UUID.randomUUID()
        val inputId = UUID.randomUUID()
        val outId = UUID.randomUUID()

        val descriptor = WorkflowDescriptor(
            schemaVersion = "1.0",
            metadata = WorkflowMetadataDescriptor(
                id = UUID.randomUUID().toString(), name = "Roundtrip WF", version = "1.0"
            ),
            steps = listOf(
                StepDescriptor(
                    id = stepId.toString(),
                    environmentId = envId.toString(),
                    metadata = StepMetadataDescriptor( name = "Step A" ),
                    task = CommandTaskDescriptor(
                        id = taskId.toString(),
                        name = "cmd",
                        executable = "python",
                        args = listOf( "run.py" ),
                    ),
                    inputs = listOf( DataPortDescriptor( id = inputId.toString() ) ),
                    outputs = listOf(
                        DataPortDescriptor(
                        id = outId.toString(),
                        descriptor = DataDescriptor( type = "csv", format = "UTF-8" )
                    )
                    ),
                )
            ),
            environments = mapOf(
                envId.toString() to EnvironmentDescriptor(
                    name = "base", kind = "conda",
                    spec = mapOf(
                        "dependencies" to listOf("numpy"),
                        "pythonVersion" to listOf("3.11"),
                        "channels" to listOf("conda-forge"),
                    )
                )
            ),
        )

        val domain = importer.import( descriptor )
        val reExported = exporter.export( domain )

        assertEquals( descriptor.schemaVersion, reExported.schemaVersion )
        assertEquals( descriptor.metadata.id, reExported.metadata.id )
        assertEquals( descriptor.metadata.name, reExported.metadata.name )
        assertEquals( descriptor.metadata.version, reExported.metadata.version )
        assertEquals( descriptor.environments.keys, reExported.environments.keys )
        assertEquals( descriptor.steps.size, reExported.steps.size )

        val origStep = descriptor.steps[0]
        val reimport = reExported.steps[0]
        assertEquals( origStep.id, reimport.id )
        assertEquals( origStep.environmentId, reimport.environmentId )
        assertEquals( origStep.task, reimport.task )
        assertEquals( origStep.inputs.map { it.id }, reimport.inputs.map { it.id } )
        assertEquals( origStep.outputs.map { it.id }, reimport.outputs.map { it.id } )
    }
    // ── Determinism across full import pipeline

    @Test
    fun `import_produces_deterministic_uuids_same_namespace_and_descriptor()`()
    {
        // Fixed namespace for reproducibility
        val namespace = UUID.parse("6ba7b810-9dad-11d1-80b4-00c04fd430c8")

        // Build a workflow descriptor once
        val descriptor = WorkflowDescriptor(
            schemaVersion = "1.0",
            metadata = WorkflowMetadataDescriptor(
                id = null, // Will be generated
                name = "Determinism Test Workflow"
            ),
            environments = mapOf(
                "conda-eeg" to EnvironmentDescriptor(
                    name = "EEG Analysis",
                    kind = "conda",
                    spec = mapOf(
                        "dependencies" to listOf("numpy", "scipy"),
                        "pythonVersion" to listOf("3.11"),
                        "channels" to listOf("conda-forge")
                    )
                )
            ),
            steps = listOf(
                StepDescriptor(
                    id = "validate-input",
                    environmentId = "conda-eeg",
                    task = CommandTaskDescriptor(
                        id = null,
                        name = "validate-eeg",
                        executable = "python",
                        args = emptyList()
                    ),
                    inputs = emptyList(),
                    outputs = emptyList()
                ),
                StepDescriptor(
                    id = "preprocess-eeg",
                    environmentId = "conda-eeg",
                    dependsOn = listOf("validate-input"),
                    task = CommandTaskDescriptor(
                        id = null,
                        name = "preprocess",
                        executable = "python",
                        args = emptyList()
                    ),
                    inputs = emptyList(),
                    outputs = emptyList()
                )
            )
        )

        // Import twice with same namespace
        val importer1 = WorkflowDescriptorImporter(namespace)
        val importer2 = WorkflowDescriptorImporter(namespace)

        val definition1 = importer1.import(descriptor)
        val definition2 = importer2.import(descriptor)

        // Verify workflow metadata UUIDs match
        assertEquals(
            definition1.workflow.metadata.id,
            definition2.workflow.metadata.id,
            "Workflow metadata UUIDs must be identical"
        )

        // Verify step UUIDs match
        val steps1 = definition1.workflow.getComponents().filterIsInstance<Step>()
        val steps2 = definition2.workflow.getComponents().filterIsInstance<Step>()

        assertEquals(steps1.size, steps2.size, "Step count must match")
        steps1.zip(steps2).forEachIndexed { index, (step1, step2) ->
            assertEquals(
                step1.metadata.id,
                step2.metadata.id,
                "Step $index metadata UUIDs must be identical"
            )
            assertEquals(
                step1.task.id,
                step2.task.id,
                "Step $index task UUIDs must be identical"
            )
            assertEquals(
                step1.environmentId,
                step2.environmentId,
                "Step $index environment UUIDs must be identical"
            )
        }

        // Verify environment UUIDs match
        assertEquals(
            definition1.environments.keys,
            definition2.environments.keys,
            "Environment UUIDs must match"
        )

        definition1.environments.keys.forEach { envId ->
            assertEquals(
                definition1.environments[envId]?.id,
                definition2.environments[envId]?.id,
                "Environment $envId UUID must be identical"
            )
        }
    }

    @Test
    fun `import_produces_different_uuids_with_different_namespaces()`()
    {
        val namespace1 = UUID.parse("6ba7b810-9dad-11d1-80b4-00c04fd430c8")
        val namespace2 = UUID.parse("6ba7b811-9dad-11d1-80b4-00c04fd430c8")

        val descriptor = WorkflowDescriptor(
            schemaVersion = "1.0",
            metadata = WorkflowMetadataDescriptor(
                id = null,
                name = "Namespace Test"
            ),
            environments = mapOf(
                "env-1" to EnvironmentDescriptor(
                    name = "Environment 1",
                    kind = "conda",
                    spec = mapOf("dependencies" to listOf("numpy"))
                )
            ),
            steps = listOf(
                StepDescriptor(
                    id = "step-1",
                    environmentId = "env-1",
                    task = CommandTaskDescriptor(
                        id = null,
                        name = "task-1",
                        executable = "echo"
                    )
                )
            )
        )

        val importer1 = WorkflowDescriptorImporter(namespace1)
        val importer2 = WorkflowDescriptorImporter(namespace2)

        val def1 = importer1.import(descriptor)
        val def2 = importer2.import(descriptor)

        // Different namespaces must produce different workflow UUIDs
        assertNotEquals(
            def1.workflow.metadata.id,
            def2.workflow.metadata.id,
            "Different namespaces must produce different workflow UUIDs"
        )
    }

    @Test
    fun `import_produces_same_uuids_regardless_of_import_order()`()
    {
        val namespace = UUID.parse("6ba7b810-9dad-11d1-80b4-00c04fd430c8")

        val descriptor = WorkflowDescriptor(
            schemaVersion = "1.0",
            metadata = WorkflowMetadataDescriptor(
                id = null,
                name = "Order Test"
            ),
            environments = mapOf(
                "env-a" to EnvironmentDescriptor(name = "A", kind = "conda", spec = emptyMap()),
                "env-b" to EnvironmentDescriptor(name = "B", kind = "pixi", spec = emptyMap())
            ),
            steps = listOf(
                StepDescriptor(id = "s1", environmentId = "env-a", task = CommandTaskDescriptor(name = "t1", executable = "echo")),
                StepDescriptor(id = "s2", environmentId = "env-b", task = CommandTaskDescriptor(name = "t2", executable = "echo"))
            )
        )

        // Import multiple times
        val results = (1..3).map {
            WorkflowDescriptorImporter(namespace).import(descriptor)
        }

        // All results should have identical workflow UUID
        val workflowUUIDs = results.map { it.workflow.metadata.id }
        kotlin.test.assertTrue(workflowUUIDs.all { it == workflowUUIDs[0] }, "All imports must produce identical workflow UUID")

        // All results should have identical step UUIDs
        results.forEach { definition ->
            val steps = definition.workflow.getComponents().filterIsInstance<Step>()
            val stepUUIDs = steps.map { it.metadata.id }
            assertEquals(listOf(results[0].workflow.getComponents()[0].metadata.id, results[0].workflow.getComponents()[1].metadata.id), stepUUIDs)
        }
    }
}
