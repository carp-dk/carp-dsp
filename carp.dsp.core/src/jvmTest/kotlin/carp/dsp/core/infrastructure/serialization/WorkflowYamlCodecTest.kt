package carp.dsp.core.infrastructure.serialization

import carp.dsp.core.application.authoring.descriptor.CommandTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.EnvironmentDescriptor
import carp.dsp.core.application.authoring.descriptor.InputRefArgDescriptor
import carp.dsp.core.application.authoring.descriptor.LiteralArgDescriptor
import carp.dsp.core.application.authoring.descriptor.ModuleEntryPointDescriptor
import carp.dsp.core.application.authoring.descriptor.OutputRefArgDescriptor
import carp.dsp.core.application.authoring.descriptor.ParamRefArgDescriptor
import carp.dsp.core.application.authoring.descriptor.PythonTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.ScriptEntryPointDescriptor
import carp.dsp.core.application.authoring.descriptor.StepDescriptor
import carp.dsp.core.application.authoring.descriptor.StepMetadataDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowMetadataDescriptor
import carp.dsp.core.application.authoring.mapper.WorkflowDescriptorExporter
import carp.dsp.core.application.environment.CondaEnvironmentDefinition
import carp.dsp.core.application.environment.PixiEnvironmentDefinition
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

/**
 * Tests for [WorkflowYamlCodec].
 *
 * Coverage:
 * - YAML encode → decode roundtrip (structural equality)
 * - Polymorphic task kinds: command, python-script, python-module
 * - All ArgToken variants
 * - Schema-version defaulting on decode
 * - Determinism: identical descriptors produce identical YAML
 * - Negative: malformed YAML, unknown type discriminator
 * - Full domain → exporter → codec roundtrip
 */
class WorkflowYamlCodecTest
{
    private val codec = WorkflowYamlCodec()
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
            id = taskId, name = "echo-task",
            executable = "echo", args = listOf(Literal("hello")),
        )
    ): Step = Step(
        metadata = StepMetadata(id = stepId, name = "Echo Step", version = Version(1, 0)),
        task = task, environmentId = envId,
    )

    private fun pythonScriptStep(): Step = Step(
        metadata = StepMetadata(id = stepId, name = "Python Step"),
        task = PythonTaskDefinition(
            id = taskId, name = "run-analysis",
            entryPoint = Script("analysis/run.py"),
            args = listOf(Literal("--verbose")),
        ),
        environmentId = envId,
    )

    private fun pythonModuleStep(): Step = Step(
        metadata = StepMetadata(id = stepId, name = "Module Step"),
        task = PythonTaskDefinition(
            id = taskId, name = "run-module",
            entryPoint = Module("mypackage.cli"),
        ),
        environmentId = envId,
    )

    // ── Encode ────────────────────────────────────────────────────────────────

    @Test
    fun `encode produces non-empty YAML string`()
    {
        val descriptor = exporter.export(makeDefinition(commandStep()))
        val yaml = codec.encode(descriptor)

        assertTrue(yaml.isNotBlank())
        assertTrue(yaml.contains("schemaVersion"))
        assertTrue(yaml.contains("Test Workflow"))
    }

    @Test
    fun `encode emits stable type discriminator for command task`()
    {
        val yaml = codec.encode(exporter.export(makeDefinition(commandStep())))
        // polymorphismPropertyName = "type" is explicit in descriptorYaml — not a library default
        assertTrue(yaml.contains("type:"), "Expected 'type:' discriminator key in:\n$yaml")
        assertTrue(yaml.contains("command"), "Expected discriminator value 'command' in:\n$yaml")
    }

    @Test
    fun `encode emits stable type discriminator for python task`()
    {
        val yaml = codec.encode(exporter.export(makeDefinition(pythonScriptStep())))
        // polymorphismPropertyName = "type" is explicit in descriptorYaml — not a library default
        assertTrue(yaml.contains("type:"), "Expected 'type:' discriminator key in:\n$yaml")
        assertTrue(yaml.contains("python"), "Expected discriminator value 'python' in:\n$yaml")
    }

    @Test
    fun `encode is deterministic - same descriptor produces identical YAML`()
    {
        val descriptor = exporter.export(makeDefinition(commandStep(), pythonScriptStep()))
        assertEquals(codec.encode(descriptor), codec.encode(descriptor))
    }

    // ── Decode ────────────────────────────────────────────────────────────────

    @Test
    fun `decodeOrThrow parses minimal valid YAML`()
    {
        val yaml = """
            schemaVersion: "1.0"
            metadata:
              name: "minimal"
            steps: []
            environments: {}
        """.trimIndent()

        val result = codec.decodeOrThrow(yaml)
        assertEquals("1.0", result.schemaVersion)
        assertEquals("minimal", result.metadata.name)
        assertTrue(result.steps.isEmpty())
    }

    @Test
    fun `decode defaults schemaVersion to 1_0 when absent`()
    {
        val yaml = """
            metadata:
              name: "no-version"
        """.trimIndent()

        val result = codec.decodeOrThrow(yaml)
        assertEquals(WorkflowYamlCodec.DEFAULT_SCHEMA_VERSION, result.schemaVersion)
    }

    @Test
    fun `decode defaults schemaVersion to 1_0 when blank`()
    {
        val yaml = """
            schemaVersion: ""
            metadata:
              name: "blank-version"
        """.trimIndent()

        val result = codec.decodeOrThrow(yaml)
        assertEquals(WorkflowYamlCodec.DEFAULT_SCHEMA_VERSION, result.schemaVersion)
    }

    @Test
    fun `decode allows null ids on steps and tasks`()
    {
        val yaml = """
            schemaVersion: "1.0"
            metadata:
              name: "nullable-ids"
            steps:
            - environmentId: "env-1"
              task:
                type: "command"
                name: "t"
                executable: "echo"
        """.trimIndent()

        val result = codec.decodeOrThrow(yaml)
        val step = result.steps.first()
        // id is nullable — absent in YAML maps to null without error
        assertEquals(null, step.id)
        val task = step.task as CommandTaskDescriptor
        assertEquals(null, task.id)
    }

    @Test
    fun `decode ignores unknown top-level fields`()
    {
        val yaml = """
            schemaVersion: "1.0"
            metadata:
              name: "extras"
            unknownField: "should be ignored"
            anotherUnknown: 42
        """.trimIndent()

        // Should not throw — strictMode = false
        val result = codec.decodeOrThrow(yaml)
        assertEquals("extras", result.metadata.name)
    }

    // ── Roundtrip ─────────────────────────────────────────────────────────────

    @Test
    fun `encode then decode roundtrip preserves command task`()
    {
        val original = exporter.export(makeDefinition(commandStep()))
        val decoded = codec.decodeOrThrow(codec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `encode then decode roundtrip preserves python script task`()
    {
        val original = exporter.export(makeDefinition(pythonScriptStep()))
        val decoded = codec.decodeOrThrow(codec.encode(original))

        assertEquals(original, decoded)
        val task = decoded.steps.first().task as PythonTaskDescriptor
        assertEquals(ScriptEntryPointDescriptor("analysis/run.py"), task.entryPoint)
    }

    @Test
    fun `encode then decode roundtrip preserves python module task`()
    {
        val original = exporter.export(makeDefinition(pythonModuleStep()))
        val decoded = codec.decodeOrThrow(codec.encode(original))

        assertEquals(original, decoded)
        val task = decoded.steps.first().task as PythonTaskDescriptor
        assertEquals(ModuleEntryPointDescriptor("mypackage.cli"), task.entryPoint)
    }

    @Test
    fun `encode then decode roundtrip preserves all ArgToken variants`()
    {
        val inputId = UUID.randomUUID()
        val outputId = UUID.randomUUID()
        val allTokensTask = CommandTaskDefinition(
            id = taskId, name = "all-tokens", executable = "tool",
            args = listOf(
                Literal("--flag"),
                InputRef(inputId),
                OutputRef(outputId),
                ParamRef("myParam"),
            ),
        )
        val original = exporter.export(makeDefinition(commandStep(allTokensTask)))
        val decoded = codec.decodeOrThrow(codec.encode(original))

        val args = (decoded.steps.first().task as CommandTaskDescriptor).args
        assertEquals(LiteralArgDescriptor("--flag"), args[0])
        assertEquals(InputRefArgDescriptor(inputId.toString()), args[1])
        assertEquals(OutputRefArgDescriptor(outputId.toString()), args[2])
        assertEquals(ParamRefArgDescriptor("myParam"), args[3])
    }

    @Test
    fun `encode then decode roundtrip preserves environments`()
    {
        val pixiEnv = PixiEnvironmentDefinition(
            id = UUID.randomUUID(), name = "pixi-env",
            dependencies = listOf("scipy"), pythonVersion = "3.12",
        )
        val id2 = UUID.randomUUID()
        val definition = WorkflowDefinition(
            workflow = makeWorkflow(),
            environments = mapOf(envId to condaEnv, id2 to pixiEnv),
        )
        val original = exporter.export(definition)
        val decoded = codec.decodeOrThrow(codec.encode(original))

        assertEquals(original, decoded)
        assertEquals(2, decoded.environments.size)
    }

    @Test
    fun `full domain to descriptor to YAML to descriptor roundtrip`()
    {
        // Domain
        val definition = makeDefinition(commandStep(), pythonScriptStep())

        // Domain → Descriptor
        val exported = exporter.export(definition)

        // Descriptor → YAML → Descriptor
        val yaml = codec.encode(exported)
        val decoded = codec.decodeOrThrow(yaml)

        assertEquals(exported, decoded)
        assertEquals(2, decoded.steps.size)
        assertNotNull(decoded.environments[envId.toString()])
    }

    @Test
    fun `roundtrip is deterministic across multiple encode-decode cycles`()
    {
        val descriptor = exporter.export(makeDefinition(commandStep(), pythonModuleStep()))
        val yaml1 = codec.encode(descriptor)
        val yaml2 = codec.encode(codec.decodeOrThrow(yaml1))

        assertEquals(yaml1, yaml2)
    }

    // ── Negative: malformed YAML ──────────────────────────────────────────────

    @Test
    fun `decodeOrThrow throws YamlCodecException for completely invalid YAML`()
    {
        val notYaml = "{{{{ this is : not : valid yaml {{{"
        assertFailsWith<YamlCodecException> {
            codec.decodeOrThrow(notYaml)
        }
    }

    @Test
    fun `decode returns failure for completely invalid YAML`()
    {
        val result = codec.decode("{{{{ this is : not : valid yaml {{{")
        assertTrue(result is DecodeResult.MalformedYaml, "Expected MalformedYaml but got $result")
    }

    @Test
    fun `decodeOrThrow throws YamlCodecException for wrong YAML structure`()
    {
        // A YAML list where a mapping is expected
        val wrongStructure = "- one\n- two\n- three"
        assertFailsWith<YamlCodecException> {
            codec.decodeOrThrow(wrongStructure)
        }
    }

    // ── Negative: unknown type discriminator ──────────────────────────────────

    @Test
    fun `decodeOrThrow throws YamlCodecException for unknown task type discriminator`()
    {
        val yaml = """
            schemaVersion: "1.0"
            metadata:
              name: "bad-type"
            steps:
            - environmentId: "env-1"
              task:
                type: "alien-task-type"
                name: "t"
                executable: "x"
        """.trimIndent()

        assertFailsWith<YamlCodecException> {
            codec.decodeOrThrow(yaml)
        }
    }

    @Test
    fun `decode returns SchemaError for unknown task type discriminator`()
    {
        val yaml = """
            schemaVersion: "1.0"
            metadata:
              name: "bad-type"
            steps:
            - environmentId: "env-1"
              task:
                type: "not-a-real-task"
                name: "t"
        """.trimIndent()

        val result = codec.decode(yaml)
        assertTrue(result is DecodeResult.SchemaError, "Expected SchemaError but got $result")
    }

    @Test
    fun `decodeOrThrow throws YamlCodecException for unknown entry point type`()
    {
        val yaml = """
            schemaVersion: "1.0"
            metadata:
              name: "bad-ep"
            steps:
            - environmentId: "env-1"
              task:
                type: "python"
                name: "t"
                entryPoint:
                  type: "jar"
                  jarPath: "/opt/app.jar"
                args: []
        """.trimIndent()

        assertFailsWith<YamlCodecException> {
            codec.decodeOrThrow(yaml)
        }
    }

    // ── Codec structure ───────────────────────────────────────────────────────

    @Test
    fun `DEFAULT_SCHEMA_VERSION is 1_0`()
    {
        assertEquals("1.0", WorkflowYamlCodec.DEFAULT_SCHEMA_VERSION)
    }

    @Test
    fun `manually constructed descriptor encodes and decodes cleanly`()
    {
        val descriptor = WorkflowDescriptor(
            schemaVersion = "1.0",
            metadata = WorkflowMetadataDescriptor(
                id = "wf-1", name = "Manual Workflow", version = "2.0"
            ),
            steps = listOf(
                StepDescriptor(
                    id = "step-1",
                    environmentId = "env-1",
                    metadata = StepMetadataDescriptor(name = "Step One"),
                    task = CommandTaskDescriptor(
                        id = "task-1", name = "greet", executable = "echo",
                        args = listOf(LiteralArgDescriptor("hello")),
                    ),
                )
            ),
            environments = mapOf(
                "env-1" to EnvironmentDescriptor(
                    name = "base", kind = "conda",
                    spec = mapOf("pythonVersion" to listOf("3.11"))
                )
            ),
        )

        val decoded = codec.decodeOrThrow(codec.encode(descriptor))
        assertEquals(descriptor, decoded)
    }
}

