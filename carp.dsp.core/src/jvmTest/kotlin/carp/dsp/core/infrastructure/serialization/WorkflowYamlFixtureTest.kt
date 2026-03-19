package carp.dsp.core.infrastructure.serialization

import carp.dsp.core.application.authoring.descriptor.CommandTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.DataPortDescriptor
import carp.dsp.core.application.authoring.descriptor.ModuleEntryPointDescriptor
import carp.dsp.core.application.authoring.descriptor.PythonTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.ScriptEntryPointDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Golden fixture tests for [WorkflowYamlCodec].
 *
 * Args are plain strings; inference rules applied by [carp.dsp.core.application.authoring.mapper.TaskImporter]:
 * - `"input.N"`    → InputRef (by index)
 * - `"output.N"`   → OutputRef (by index)
 * - `"param:NAME"` → ParamRef
 * - anything else  → Literal
 */
class WorkflowYamlFixtureTest
{
    private val codec = WorkflowYamlCodec()

    // ── Fixture loading ───────────────────────────────────────────────────────

    @Suppress("SameParameterValue")
    private fun loadFixture( name: String ): String =
        checkNotNull(
            WorkflowYamlFixtureTest::class.java.classLoader
                .getResourceAsStream("integration-fixtures/$name")
        ) { "Fixture not found: fixtures/$name" }
            .bufferedReader()
            .readText()

    // ── Decode: fixture → descriptor ──────────────────────────────────────────

    @Test
    fun `fixture decodes without error`()
    {
        val yaml = loadFixture("workflow-complex-v1.yml")
        val result = codec.decode(yaml)
        assertIs<DecodeResult.Success>(result, "Expected Success but got $result")
    }

    @Test
    fun `fixture decode - workflow metadata`()
    {
        val descriptor = decodeFixture()

        assertEquals("1.0", descriptor.schemaVersion)
        assertEquals("a1b2c3d4-0000-0000-0000-000000000001", descriptor.metadata.id)
        assertEquals("Signal Processing Pipeline", descriptor.metadata.name)
        assertEquals("2.0", descriptor.metadata.version)
        assertEquals(listOf("eeg", "signal-processing", "research"), descriptor.metadata.tags)
        assertNotNull(descriptor.metadata.description)
    }

    @Test
    fun `fixture decode - environments`()
    {
        val descriptor = decodeFixture()

        assertEquals(2, descriptor.environments.size)

        val conda = descriptor.environments["env-conda-001"]
        assertNotNull(conda, "Expected env-conda-001")
        assertEquals("conda", conda.kind)
        assertEquals("eeg-analysis", conda.name)

        val pixi = descriptor.environments["env-pixi-002"]
        assertNotNull(pixi, "Expected env-pixi-002")
        assertEquals("pixi", pixi.kind)
        assertEquals("report-gen", pixi.name)
    }

    @Test
    fun `fixture decode - step count and environment references`()
    {
        val descriptor = decodeFixture()

        assertEquals(4, descriptor.steps.size)
        assertEquals("env-conda-001", descriptor.steps[0].environmentId)
        assertEquals("env-conda-001", descriptor.steps[1].environmentId)
        assertEquals("env-conda-001", descriptor.steps[2].environmentId)
        assertEquals("env-pixi-002", descriptor.steps[3].environmentId)
    }

    @Test
    fun `fixture decode - step dependencies`()
    {
        val descriptor = decodeFixture()

        assertTrue(descriptor.steps[0].dependsOn.isEmpty(), "step-001 should have no dependencies")
        assertEquals(listOf("step-001"), descriptor.steps[1].dependsOn)
        assertEquals(listOf("step-002"), descriptor.steps[2].dependsOn)
        assertEquals(listOf("step-003"), descriptor.steps[3].dependsOn)
    }

    @Test
    fun `fixture decode - command task (step-001)`()
    {
        val step = decodeFixture().steps[0]
        assertEquals("step-001", step.id)

        val task = assertIs<CommandTaskDescriptor>(step.task)
        assertEquals("task-001", task.id)
        assertEquals("validate-eeg", task.name)
        assertEquals("python", task.executable)
        assertNotNull(task.description)
        assertEquals(3, task.args.size)
    }

    @Test
    fun `fixture decode - python script task (step-002)`()
    {
        val step = decodeFixture().steps[1]
        assertEquals("step-002", step.id)

        val task = assertIs<PythonTaskDescriptor>(step.task)
        assertEquals("task-002", task.id)
        assertEquals("preprocess", task.name)
        assertEquals(ScriptEntryPointDescriptor("pipeline/preprocess.py"), task.entryPoint)
        assertEquals(3, task.args.size)
    }

    @Test
    fun `fixture decode - python module task (step-003)`()
    {
        val step = decodeFixture().steps[2]
        assertEquals("step-003", step.id)

        val task = assertIs<PythonTaskDescriptor>(step.task)
        assertEquals("task-003", task.id)
        assertEquals("extract-features", task.name)
        assertEquals(ModuleEntryPointDescriptor("eeg_pipeline.features"), task.entryPoint)
        assertEquals(2, task.args.size)
    }

    @Test
    fun `fixture decode - all arg token string variants are present`()
    {
        val descriptor = decodeFixture()

        // step-001: literal, input-ref, param-ref
        val step1Task = assertIs<CommandTaskDescriptor>(descriptor.steps[0].task)
        assertEquals("validate_eeg.py", step1Task.args[0]) // literal
        assertEquals("input.0", step1Task.args[1]) // input-ref by index
        assertEquals("param:strict_mode", step1Task.args[2]) // param-ref

        // step-002: input-ref, output-ref, literal
        val step2Task = assertIs<PythonTaskDescriptor>(descriptor.steps[1].task)
        assertEquals("input.0", step2Task.args[0]) // input-ref
        assertEquals("output.0", step2Task.args[1]) // output-ref
        assertEquals("--notch-freq=50", step2Task.args[2]) // literal
    }

    @Test
    fun `fixture decode - data ports are preserved`()
    {
        val descriptor = decodeFixture()

        // step-001: 1 input with descriptor, 0 outputs
        val step1 = descriptor.steps[0]
        assertEquals(1, step1.inputs.size)
        assertEquals("port-raw-eeg-001", step1.inputs[0].id)
        assertEquals("edf", step1.inputs[0].descriptor?.type)
        assertEquals(0, step1.outputs.size)

        // step-002: 1 input (no descriptor), 1 output (with descriptor)
        val step2 = descriptor.steps[1]
        assertEquals(1, step2.inputs.size)
        assertEquals("port-raw-eeg-001", step2.inputs[0].id)
        assertIs<DataPortDescriptor>(step2.inputs[0])
        assertEquals(1, step2.outputs.size)
        assertEquals("port-clean-eeg-002", step2.outputs[0].id)
        assertEquals("edf", step2.outputs[0].descriptor?.type)
    }

    @Test
    fun `fixture decode - optional fields absent where omitted`()
    {
        val descriptor = decodeFixture()

        // step-004 task has no description in the fixture
        val task4 = assertIs<CommandTaskDescriptor>(descriptor.steps[3].task)
        assertEquals(null, task4.description)

        // step-002 input port has no descriptor
        assertEquals(null, descriptor.steps[1].inputs[0].descriptor)
    }

    // ── Golden: normalised roundtrip ──────────────────────────────────────────

    @Test
    fun `golden - decode then encode then decode produces identical descriptor`()
    {
        val fixtureYaml = loadFixture("workflow-complex-v1.yml")
        val first = decodeFixture(fixtureYaml)
        val reEncoded = codec.encode(first)
        val second = codec.decodeOrThrow(reEncoded)

        assertEquals(
            first, second,
            "Re-encoded YAML decoded to a different descriptor.\nRe-encoded YAML was:\n$reEncoded"
        )
    }

    @Test
    fun `golden - encode is stable across multiple cycles`()
    {
        val yaml1 = codec.encode(decodeFixture())
        val yaml2 = codec.encode(codec.decodeOrThrow(yaml1))

        assertEquals(
            yaml1, yaml2,
            "Second encode-decode cycle produced different YAML.\nFirst:\n$yaml1\n\nSecond:\n$yaml2"
        )
    }

    @Test
    fun `golden - all four steps survive roundtrip`()
    {
        val original = decodeFixture()
        val roundtrip = codec.decodeOrThrow(codec.encode(original))

        assertEquals(original.steps.size, roundtrip.steps.size)
        original.steps.zip(roundtrip.steps).forEachIndexed { i, (a, b) ->
            assertEquals(a, b, "Step $i differs after roundtrip")
        }
    }

    @Test
    fun `golden - environments survive roundtrip`()
    {
        val original = decodeFixture()
        val roundtrip = codec.decodeOrThrow(codec.encode(original))

        assertEquals(original.environments, roundtrip.environments)
    }

    @Test
    fun `golden - encoded YAML contains explicit type discriminators for task kinds`()
    {
        val yaml = codec.encode(decodeFixture())

        assertTrue(
            yaml.contains("type: \"command\"") || yaml.contains("type: command"),
            "Expected 'command' discriminator in encoded YAML"
        )
        assertTrue(
            yaml.contains("type: \"python\"") || yaml.contains("type: python"),
            "Expected 'python' discriminator in encoded YAML"
        )
        assertTrue(
            yaml.contains("type: \"script\"") || yaml.contains("type: script"),
            "Expected 'script' entry-point discriminator in encoded YAML"
        )
        assertTrue(
            yaml.contains("type: \"module\"") || yaml.contains("type: module"),
            "Expected 'module' entry-point discriminator in encoded YAML"
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun decodeFixture( yaml: String = loadFixture("workflow-complex-v1.yml") ): WorkflowDescriptor =
        codec.decodeOrThrow(yaml)
}
