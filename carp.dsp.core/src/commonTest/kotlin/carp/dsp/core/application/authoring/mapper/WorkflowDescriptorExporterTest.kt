package carp.dsp.core.application.authoring.mapper

import carp.dsp.core.application.authoring.descriptor.CommandTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.InputRefArgDescriptor
import carp.dsp.core.application.authoring.descriptor.LiteralArgDescriptor
import carp.dsp.core.application.authoring.descriptor.ModuleEntryPointDescriptor
import carp.dsp.core.application.authoring.descriptor.OutputRefArgDescriptor
import carp.dsp.core.application.authoring.descriptor.ParamRefArgDescriptor
import carp.dsp.core.application.authoring.descriptor.PythonTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.ScriptEntryPointDescriptor
import carp.dsp.core.application.authoring.descriptor.StepDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor
import carp.dsp.core.application.authoring.serialization.descriptorYaml
import carp.dsp.core.application.environment.CondaEnvironmentDefinition
import carp.dsp.core.application.environment.PixiEnvironmentDefinition
import dk.cachet.carp.analytics.domain.data.FileDestination
import dk.cachet.carp.analytics.domain.data.FileFormat
import dk.cachet.carp.analytics.domain.data.FileSystemSource
import dk.cachet.carp.analytics.domain.data.InputDataSpec
import dk.cachet.carp.analytics.domain.data.OutputDataSpec
import dk.cachet.carp.analytics.domain.tasks.CommandTaskDefinition
import dk.cachet.carp.analytics.domain.tasks.InputRef
import dk.cachet.carp.analytics.domain.tasks.Literal
import dk.cachet.carp.analytics.domain.tasks.Module
import dk.cachet.carp.analytics.domain.tasks.OutputRef
import dk.cachet.carp.analytics.domain.tasks.ParamRef
import dk.cachet.carp.analytics.domain.tasks.PythonTaskDefinition
import dk.cachet.carp.analytics.domain.tasks.Script
import dk.cachet.carp.analytics.domain.workflow.Step
import dk.cachet.carp.analytics.domain.workflow.StepMetadata
import dk.cachet.carp.analytics.domain.workflow.Version
import dk.cachet.carp.analytics.domain.workflow.Workflow
import dk.cachet.carp.analytics.domain.workflow.WorkflowDefinition
import dk.cachet.carp.analytics.domain.workflow.WorkflowMetadata
import dk.cachet.carp.common.application.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkflowDescriptorExporterTest
{
    private val exporter = WorkflowDescriptorExporter()

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private val envId = UUID.randomUUID()
    private val stepId = UUID.randomUUID()
    private val taskId = UUID.randomUUID()

    private val condaEnv = CondaEnvironmentDefinition(
        id = envId,
        name = "test-env",
        dependencies = listOf("numpy", "pandas"),
        pythonVersion = "3.11",
        channels = listOf("conda-forge"),
    )

    private fun makeWorkflow( vararg steps: Step ): Workflow
    {
        val wf = Workflow(
            metadata = WorkflowMetadata(
                id = UUID.randomUUID(),
                name = "Test Workflow",
                description = "A test workflow",
                version = Version(1, 0),
            )
        )
        steps.forEach { wf.addComponent(it) }
        return wf
    }

    private fun makeDefinition( vararg steps: Step ): WorkflowDefinition =
        WorkflowDefinition(
            workflow = makeWorkflow(*steps),
            environments = mapOf(envId to condaEnv),
        )

    private fun commandStep(
        task: CommandTaskDefinition = CommandTaskDefinition(
            id = taskId,
            name = "echo-task",
            executable = "echo",
            args = listOf(Literal("hello")),
        )
    ): Step = Step(
        metadata = StepMetadata(id = stepId, name = "Echo Step", version = Version(1, 0)),
        task = task,
        environmentId = envId,
    )

    private fun pythonScriptStep(): Step = Step(
        metadata = StepMetadata(id = stepId, name = "Python Step"),
        task = PythonTaskDefinition(
            id = taskId,
            name = "run-analysis",
            entryPoint = Script("analysis/run.py"),
            args = listOf(Literal("--verbose")),
        ),
        environmentId = envId,
    )

    private fun pythonModuleStep(): Step = Step(
        metadata = StepMetadata(id = stepId, name = "Module Step"),
        task = PythonTaskDefinition(
            id = taskId,
            name = "run-module",
            entryPoint = Module("mypackage.cli"),
        ),
        environmentId = envId,
    )

    // ── Snapshot test ─────────────────────────────────────────────────────────

    @Test
    fun `export produces expected WorkflowDescriptor snapshot`()
    {
        val definition = makeDefinition(commandStep())
        val result = exporter.export(definition, schemaVersion = "1.0")

        // Top-level shape
        assertEquals("1.0", result.schemaVersion)
        assertEquals("Test Workflow", result.metadata.name)
        assertEquals("A test workflow", result.metadata.description)
        assertEquals("1.0", result.metadata.version)
        assertNotNull(result.metadata.id, "metadata.id must be non-null on export")

        // Exactly one step
        assertEquals(1, result.steps.size)
        val step = result.steps.first()
        assertEquals(stepId.toString(), step.id)
        assertEquals("Echo Step", step.metadata?.name)
        assertEquals(envId.toString(), step.environmentId)

        // Task
        val task = step.task as CommandTaskDescriptor
        assertEquals(taskId.toString(), task.id)
        assertEquals("echo-task", task.name)
        assertEquals("echo", task.executable)
        assertEquals(1, task.args.size)
        assertEquals(LiteralArgDescriptor("hello"), task.args.first())

        // Environment keyed by UUID string
        assertEquals(1, result.environments.size)
        val envEntry = result.environments[envId.toString()]
        assertNotNull(envEntry)
        assertEquals("test-env", envEntry.name)
        assertEquals("conda", envEntry.kind)
    }

    // ── Determinism test ──────────────────────────────────────────────────────

    @Test
    fun `export is deterministic - same domain object produces identical descriptors`()
    {
        val definition = makeDefinition(commandStep(), pythonScriptStep())
        val first = exporter.export(definition)
        val second = exporter.export(definition)
        assertEquals(first, second)
    }

    @Test
    fun `export is deterministic - environments are sorted by key`()
    {
        // Use two UUIDs where we know the lexicographic string order
        val id1 = UUID.randomUUID()
        val id2 = UUID.randomUUID()
        val env1 = CondaEnvironmentDefinition(id = id1, name = "env-1")
        val env2 = CondaEnvironmentDefinition(id = id2, name = "env-2")

        val wf = makeWorkflow()
        // Insert in both orders — exporter should always produce sorted keys
        val definitionAB = WorkflowDefinition(
            workflow = wf,
            environments = mapOf<UUID, dk.cachet.carp.analytics.domain.environment.EnvironmentDefinition>(
                id1 to env1, id2 to env2
            )
        )
        val definitionBA = WorkflowDefinition(
            workflow = wf,
            environments = mapOf<UUID, dk.cachet.carp.analytics.domain.environment.EnvironmentDefinition>(
                id2 to env2, id1 to env1
            )
        )

        val keysAB = exporter.export(definitionAB).environments.keys.toList()
        val keysBA = exporter.export(definitionBA).environments.keys.toList()

        // Both must yield the same sorted order
        assertEquals(keysAB, keysBA)
        // Keys must actually be sorted lexicographically
        assertEquals(keysAB, keysAB.sorted())
    }

    // ── Command task export ───────────────────────────────────────────────────

    @Test
    fun `exportCommandTask maps all fields correctly`()
    {
        val inputId = UUID.randomUUID()
        val outputId = UUID.randomUUID()
        val task = CommandTaskDefinition(
            id = taskId,
            name = "copy-task",
            description = "copies files",
            executable = "cp",
            args = listOf(
                Literal("--verbose"),
                InputRef(inputId),
                OutputRef(outputId),
                ParamRef("flag"),
            ),
        )

        val result = exporter.exportCommandTask(task)

        assertEquals(taskId.toString(), result.id)
        assertEquals("copy-task", result.name)
        assertEquals("copies files", result.description)
        assertEquals("cp", result.executable)
        assertEquals(4, result.args.size)
        assertEquals(LiteralArgDescriptor("--verbose"), result.args[0])
        assertEquals(InputRefArgDescriptor(inputId.toString()), result.args[1])
        assertEquals(OutputRefArgDescriptor(outputId.toString()), result.args[2])
        assertEquals(ParamRefArgDescriptor("flag"), result.args[3])
    }

    // ── Python task export ────────────────────────────────────────────────────

    @Test
    fun `exportPythonTask with Script entry point maps correctly`()
    {
        val step = pythonScriptStep()
        val result = exporter.exportStep(step)
        val task = result.task as PythonTaskDescriptor

        assertEquals(taskId.toString(), task.id)
        assertEquals("run-analysis", task.name)
        assertEquals(ScriptEntryPointDescriptor("analysis/run.py"), task.entryPoint)
        assertEquals(listOf(LiteralArgDescriptor("--verbose")), task.args)
    }

    @Test
    fun `exportPythonTask with Module entry point maps correctly`()
    {
        val step = pythonModuleStep()
        val result = exporter.exportStep(step)
        val task = result.task as PythonTaskDescriptor

        assertEquals(ModuleEntryPointDescriptor("mypackage.cli"), task.entryPoint)
        assertTrue(task.args.isEmpty())
    }

    // ── ArgToken variants ─────────────────────────────────────────────────────

    @Test
    fun `exportArgToken maps Literal`()
    {
        assertEquals(LiteralArgDescriptor("hello"), exporter.exportArgToken(Literal("hello")))
    }

    @Test
    fun `exportArgToken maps InputRef`()
    {
        val id = UUID.randomUUID()
        assertEquals(InputRefArgDescriptor(id.toString()), exporter.exportArgToken(InputRef(id)))
    }

    @Test
    fun `exportArgToken maps OutputRef`()
    {
        val id = UUID.randomUUID()
        assertEquals(OutputRefArgDescriptor(id.toString()), exporter.exportArgToken(OutputRef(id)))
    }

    @Test
    fun `exportArgToken maps ParamRef`()
    {
        assertEquals(ParamRefArgDescriptor("myParam"), exporter.exportArgToken(ParamRef("myParam")))
    }

    // ── Environment export ────────────────────────────────────────────────────

    @Test
    fun `exportEnvironment maps CondaEnvironmentDefinition to kind conda`()
    {
        val result = exporter.exportEnvironment(condaEnv)
        assertEquals("conda", result.kind)
        assertEquals("test-env", result.name)
        assertTrue(result.spec.containsKey("pythonVersion"))
        assertTrue(result.spec.containsKey("channels"))
        assertTrue(result.spec.containsKey("dependencies"))
    }

    @Test
    fun `exportEnvironment maps PixiEnvironmentDefinition to kind pixi`()
    {
        val pixiEnv = PixiEnvironmentDefinition(
            id = UUID.randomUUID(),
            name = "pixi-env",
            dependencies = listOf("scipy"),
            pythonVersion = "3.12",
        )
        val result = exporter.exportEnvironment(pixiEnv)
        assertEquals("pixi", result.kind)
        assertEquals("pixi-env", result.name)
        assertTrue(result.spec.containsKey("pythonVersion"))
    }

    @Test
    fun `exportEnvironment falls back to kind unknown for unrecognised implementations`()
    {
        val unknown = object : dk.cachet.carp.analytics.domain.environment.EnvironmentDefinition
        {
            override val id = UUID.randomUUID()
            override val name = "alien-env"
            override val dependencies = emptyList<String>()
            override val environmentVariables = emptyMap<String, String>()
        }
        val result = exporter.exportEnvironment(unknown)
        assertEquals("unknown", result.kind)
    }

    // ── Strict-write: IDs are never null ──────────────────────────────────────

    @Test
    fun `export always emits non-null ids for steps and tasks`()
    {
        val descriptor: WorkflowDescriptor = exporter.export(makeDefinition(commandStep()))
        descriptor.steps.forEach { step: StepDescriptor ->
            assertNotNull(step.id, "step.id must not be null in exported descriptor")
            assertNotNull(step.task.id, "task.id must not be null in exported descriptor")
        }
    }

    @Test
    fun `export always emits non-null workflow id`()
    {
        val result = exporter.export(makeDefinition(commandStep()))
        assertNotNull(result.metadata.id)
    }

    // ── Error handling ────────────────────────────────────────────────────────

    @Test
    fun `exportTask throws UnsupportedTaskTypeException for unknown task type`()
    {
        val unknownTask = object : dk.cachet.carp.analytics.domain.tasks.TaskDefinition
        {
            override val id = UUID.randomUUID()
            override val name = "alien"
            override val description = null
        }
        assertFailsWith<UnsupportedTaskTypeException> {
            exporter.exportTask(unknownTask)
        }
    }

    // ── Data ports ────────────────────────────────────────────────────────────

    @Test
    fun `exportStep maps input and output ports with correct ids`()
    {
        val inputId = UUID.randomUUID()
        val outputId = UUID.randomUUID()

        val step = Step(
            metadata = StepMetadata(id = stepId, name = "Port Step"),
            task = CommandTaskDefinition(id = taskId, name = "t", executable = "echo"),
            environmentId = envId,
            inputs = listOf(
                InputDataSpec(
                    id = inputId,
                    name = "raw-data",
                    source = FileSystemSource(path = "/data/in.csv", format = FileFormat.CSV),
                )
            ),
            outputs = listOf(
                OutputDataSpec(
                    id = outputId,
                    name = "result",
                    destination = FileDestination(path = "/data/out.json", format = FileFormat.JSON),
                )
            ),
        )

        val result = exporter.exportStep(step)

        assertEquals(1, result.inputs.size)
        assertEquals(inputId.toString(), result.inputs.first().id)
        assertEquals(1, result.outputs.size)
        assertEquals(outputId.toString(), result.outputs.first().id)
    }

    // ── YAML serialization roundtrip ──────────────────────────────────────────
    // YAML is the primary document contract for WorkflowDescriptor.
    // These tests verify that the full sealed-type hierarchy survives an
    // encode → decode cycle through descriptorYaml (kaml), which is what
    // authored .yaml files and CI pipelines consume.

    @Test
    fun `WorkflowDescriptor survives YAML roundtrip for command task`()
    {
        val original = exporter.export(makeDefinition(commandStep()))
        val yaml = descriptorYaml.encodeToString(WorkflowDescriptor.serializer(), original)
        val decoded = descriptorYaml.decodeFromString(WorkflowDescriptor.serializer(), yaml)

        assertEquals(original, decoded)
        // Discriminator field must be present in the emitted YAML
        assertTrue(yaml.contains("type:"), "YAML must contain 'type:' discriminator, got:\n$yaml")
    }

    @Test
    fun `WorkflowDescriptor survives YAML roundtrip for python script task`()
    {
        val original = exporter.export(makeDefinition(pythonScriptStep()))
        val yaml = descriptorYaml.encodeToString(WorkflowDescriptor.serializer(), original)
        val decoded = descriptorYaml.decodeFromString(WorkflowDescriptor.serializer(), yaml)

        assertEquals(original, decoded)

        val task = decoded.steps.first().task as PythonTaskDescriptor
        assertEquals(ScriptEntryPointDescriptor("analysis/run.py"), task.entryPoint)
        // Check discriminator is present (value may be quoted or unquoted depending on kaml version)
        assertTrue(yaml.contains("type:"), "YAML must contain 'type:' discriminator, got:\n$yaml")
        assertTrue(yaml.contains("python"), "YAML must contain task kind 'python', got:\n$yaml")
    }

    @Test
    fun `WorkflowDescriptor survives YAML roundtrip for python module task`()
    {
        val original = exporter.export(makeDefinition(pythonModuleStep()))
        val decoded = descriptorYaml.decodeFromString(
            WorkflowDescriptor.serializer(),
            descriptorYaml.encodeToString(WorkflowDescriptor.serializer(), original)
        )

        assertEquals(original, decoded)

        val task = decoded.steps.first().task as PythonTaskDescriptor
        assertEquals(ModuleEntryPointDescriptor("mypackage.cli"), task.entryPoint)
    }

    @Test
    fun `WorkflowDescriptor YAML roundtrip preserves all ArgToken variants`()
    {
        val inputId = UUID.randomUUID()
        val outputId = UUID.randomUUID()
        val allTokensTask = CommandTaskDefinition(
            id = taskId,
            name = "all-tokens",
            executable = "tool",
            args = listOf(
                Literal("--flag"),
                InputRef(inputId),
                OutputRef(outputId),
                ParamRef("myParam"),
            ),
        )

        val original = exporter.export(makeDefinition(commandStep(allTokensTask)))
        val decoded = descriptorYaml.decodeFromString(
            WorkflowDescriptor.serializer(),
            descriptorYaml.encodeToString(WorkflowDescriptor.serializer(), original)
        )

        val args = (decoded.steps.first().task as CommandTaskDescriptor).args
        assertEquals(LiteralArgDescriptor("--flag"), args[0])
        assertEquals(InputRefArgDescriptor(inputId.toString()), args[1])
        assertEquals(OutputRefArgDescriptor(outputId.toString()), args[2])
        assertEquals(ParamRefArgDescriptor("myParam"), args[3])
    }

    @Test
    fun `exported YAML contains stable type discriminator for each task kind`()
    {
        val commandYaml = descriptorYaml.encodeToString(
            WorkflowDescriptor.serializer(),
            exporter.export(makeDefinition(commandStep()))
        )
        val pythonYaml = descriptorYaml.encodeToString(
            WorkflowDescriptor.serializer(),
            exporter.export(makeDefinition(pythonScriptStep()))
        )

        // Value may appear as 'type: command' or 'type: "command"' depending on kaml version
        assertTrue(commandYaml.contains("type:"), "Expected 'type:' discriminator in:\n$commandYaml")
        assertTrue(commandYaml.contains("command"), "Expected kind 'command' in:\n$commandYaml")
        assertTrue(pythonYaml.contains("type:"), "Expected 'type:' discriminator in:\n$pythonYaml")
        assertTrue(pythonYaml.contains("python"), "Expected kind 'python' in:\n$pythonYaml")
    }
}
