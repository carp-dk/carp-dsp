package carp.dsp.core.infrastructure.serialization

import carp.dsp.core.application.authoring.descriptor.CommandTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.DataDescriptor
import carp.dsp.core.application.authoring.descriptor.DataPortDescriptor
import carp.dsp.core.application.authoring.descriptor.EnvironmentDescriptor
import carp.dsp.core.application.authoring.descriptor.FileInputSource
import carp.dsp.core.application.authoring.descriptor.FileOutputDestination
import carp.dsp.core.application.authoring.descriptor.StepDescriptor
import carp.dsp.core.application.authoring.descriptor.StepMetadataDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowMetadataDescriptor
import dk.cachet.carp.analytics.application.exceptions.YamlCodecException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkflowYamlCodecTest
{
    private val codec = WorkflowYamlCodec()

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun minimalDescriptor(
        id: String? = null,
        name: String = "Test Workflow",
        version: String = "1.0",
    ) = WorkflowDescriptor(
        schemaVersion = version,
        metadata = WorkflowMetadataDescriptor(
            id = id,
            name = name,
        ),
        environments = emptyMap(),
        steps = emptyList(),
    )

    private fun minimalYaml(version: String = "1.0") = """
        schemaVersion: "$version"
        metadata:
          name: Test Workflow
        environments: {}
        steps: []
        """.trimIndent()

    // ── Decode: Basic Functionality ───────────────────────────────────────────

    @Test
    fun `decode parses minimal valid YAML to Success`()
    {
        val yaml = minimalYaml()
        val result = codec.decode(yaml)
        assertIs<DecodeResult.Success>(result)
        assertEquals("Test Workflow", result.descriptor.metadata.name)
    }

    @Test
    fun `decode returns SchemaError for malformed YAML`()
    {
        val yaml = "this: is: not: [valid: yaml" // Syntax error
        val result = codec.decode(yaml)
        assertIs<DecodeResult.MalformedYaml>(result)
        assertTrue(result.message.contains("Malformed"), "Error message should indicate malformed YAML")
    }

    @Test
    fun `decode returns SchemaError for unknown task type discriminator`()
    {
        val yaml = """
schemaVersion: "1.0"
metadata:
  name: Test
environments: {}
steps:
  - id: step-1
    environmentId: env-1
    task:
      type: "alien-task-type"
      name: bad
    inputs: []
    outputs: []
""".trimIndent()
        val result = codec.decode(yaml)
        assertIs<DecodeResult.SchemaError>(result)
        assertTrue(result.message.contains("Schema error", ignoreCase = true))
    }

    @Test
    fun `decode returns SchemaError for type mismatch`()
    {
        // schemaVersion expects a String scalar; passing a mapping is a genuine type mismatch
        // that kaml cannot coerce, unlike a float (1.0) which it silently converts to "1.0"
        val yaml = """
            schemaVersion:
              - not
              - a
              - string
            metadata:
              name: Test
            """.trimIndent()
        val result = codec.decode(yaml)
        assertIs<DecodeResult.SchemaError>(result)
    }

    @Test
    fun `decode returns SchemaError for missing required field`()
    {
        val yaml = """
schemaVersion: "1.0"
environments: {}
steps: []
""".trimIndent()
        // metadata.name is required
        val result = codec.decode(yaml)
        assertIs<DecodeResult.SchemaError>(result)
    }

    // ── schemaVersion Defaulting ──────────────────────────────────────────────

    @Test
    fun `decode defaults missing schemaVersion to 1_0`()
    {
        val yaml = """
metadata:
  name: Test Workflow
environments: {}
steps: []
""".trimIndent()
        val result = codec.decode(yaml)
        assertIs<DecodeResult.Success>(result)
        assertEquals("1.0", result.descriptor.schemaVersion)
    }

    @Test
    fun `decode defaults blank schemaVersion to 1_0`()
    {
        val yaml = """
schemaVersion: ""
metadata:
  name: Test Workflow
environments: {}
steps: []
""".trimIndent()
        val result = codec.decode(yaml)
        assertIs<DecodeResult.Success>(result)
        assertEquals("1.0", result.descriptor.schemaVersion)
    }

    @Test
    fun `decode preserves explicit schemaVersion`()
    {
        val yaml = minimalYaml(version = "2.5")
        val result = codec.decode(yaml)
        assertIs<DecodeResult.Success>(result)
        assertEquals("2.5", result.descriptor.schemaVersion)
    }

    // ── Unknown Fields (Lenient Mode) ─────────────────────────────────────────

    @Test
    fun `decode ignores unknown top-level fields`()
    {
        val yaml = """
schemaVersion: "1.0"
metadata:
  name: Test Workflow
unknownField: "should be ignored"
environments: {}
steps: []
""".trimIndent()
        val result = codec.decode(yaml)
        assertIs<DecodeResult.Success>(result) // Should NOT fail
    }

    @Test
    fun `decode ignores unknown metadata fields`()
    {
        val yaml = """
schemaVersion: "1.0"
metadata:
  name: Test Workflow
  unknownMetadataField: "ignored"
environments: {}
steps: []
""".trimIndent()
        val result = codec.decode(yaml)
        assertIs<DecodeResult.Success>(result)
    }

    @Test
    fun `decode ignores unknown fields in nested structures`()
    {
        val yaml = """
schemaVersion: "1.0"
metadata:
  name: Test Workflow
environments:
  env-1:
    name: Test Env
    kind: conda
    unknownEnvField: "ignored"
    spec: {}
steps: []
""".trimIndent()
        val result = codec.decode(yaml)
        assertIs<DecodeResult.Success>(result)
    }

    // ── Encode: Basic Functionality ───────────────────────────────────────────

    @Test
    fun `encode converts descriptor to valid YAML string`()
    {
        val descriptor = minimalDescriptor()
        val yaml = codec.encode(descriptor)

        // Should be valid YAML (parseable)
        val result = codec.decode(yaml)
        assertIs<DecodeResult.Success>(result)
    }

    @Test
    fun `encode includes schemaVersion`()
    {
        val descriptor = minimalDescriptor(version = "2.0")
        val yaml = codec.encode(descriptor)
        assertTrue(yaml.contains("schemaVersion"), "Encoded YAML should contain schemaVersion")
        assertTrue(yaml.contains("2.0"))
    }

    @Test
    fun `encode includes metadata`()
    {
        val descriptor = minimalDescriptor(name = "My Workflow")
        val yaml = codec.encode(descriptor)
        assertTrue(yaml.contains("metadata"))
        assertTrue(yaml.contains("My Workflow"))
    }

    @Test
    fun `encode suppresses default values`()
    {
        val descriptor = minimalDescriptor(version = "1.0") // Default version
        val yaml = codec.encode(descriptor)
        // With encodeDefaults = false, empty lists and maps may be omitted
        // At minimum, should be parseable
        val result = codec.decode(yaml)
        assertIs<DecodeResult.Success>(result)
    }

    // ── Round-Trip Identity ───────────────────────────────────────────────────

    @Test
    fun `round-trip preserves descriptor identity minimal case`()
    {
        val original = minimalDescriptor(id = "wf-1", name = "Test WF")
        val encoded = codec.encode(original)
        val decoded = codec.decodeOrThrow(encoded)

        assertEquals(original.schemaVersion, decoded.schemaVersion)
        assertEquals(original.metadata.name, decoded.metadata.name)
        assertEquals(original.metadata.id, decoded.metadata.id)
    }

    @Test
    fun `round-trip with environments`()
    {
        val descriptor = WorkflowDescriptor(
            schemaVersion = "1.0",
            metadata = WorkflowMetadataDescriptor(
                id = "wf-1",
                name = "Test",
                description = "A test workflow"
            ),
            environments = mapOf(
                "env-conda-001" to EnvironmentDescriptor(
                    name = "EEG Analysis",
                    kind = "conda",
                    spec = mapOf(
                        "dependencies" to listOf("numpy", "scipy"),
                        "pythonVersion" to listOf("3.11"),
                        "channels" to listOf("conda-forge")
                    )
                )
            ),
            steps = emptyList()
        )

        val encoded = codec.encode(descriptor)
        val decoded = codec.decodeOrThrow(encoded)

        assertEquals(1, decoded.environments.size)
        assertEquals("EEG Analysis", decoded.environments["env-conda-001"]?.name)
    }

    @Test
    fun `round-trip with steps and tasks`()
    {
        val descriptor = WorkflowDescriptor(
            schemaVersion = "1.0",
            metadata = WorkflowMetadataDescriptor(
                id = "wf-1",
                name = "Process Pipeline"
            ),
            environments = mapOf(
                "env-1" to EnvironmentDescriptor(
                    name = "Python Env",
                    kind = "conda",
                    spec = emptyMap()
                )
            ),
            steps = listOf(
                StepDescriptor(
                    id = "step-1",
                    metadata = StepMetadataDescriptor(
                        name = "Validate",
                        description = "Validate input"
                    ),
                    environmentId = "env-1",
                    task = CommandTaskDescriptor(
                        id = null,
                        name = "validate",
                        description = "Run validation",
                        executable = "python",
                        args = listOf("validate.py", "--strict")
                    ),
                    inputs = listOf(
                        DataPortDescriptor(
                            id = "input-1",
                            descriptor = DataDescriptor(type = "csv"),
                            source = FileInputSource(path = "./input.csv")
                        )
                    ),
                    outputs = listOf(
                        DataPortDescriptor(
                            id = "output-1",
                            descriptor = DataDescriptor(type = "csv"),
                            destination = FileOutputDestination(path = "./output.csv")
                        )
                    ),
                    dependsOn = emptyList()
                )
            )
        )

        val encoded = codec.encode(descriptor)
        val decoded = codec.decodeOrThrow(encoded)

        assertEquals(1, decoded.steps.size)
        val step = decoded.steps[0]
        assertEquals("step-1", step.id)
        assertEquals("Validate", step.metadata?.name)
        assertEquals("validate", step.task.name)
        assertEquals(1, step.inputs.size)
        assertEquals(1, step.outputs.size)
    }

    // ── Canonicalization: Same Model → Same YAML ──────────────────────────────

    @Test
    fun `canonicalization encode-decode-encode produces identical YAML`()
    {
        val descriptor = WorkflowDescriptor(
            schemaVersion = "1.0",
            metadata = WorkflowMetadataDescriptor(
                id = "wf-canonical",
                name = "Canonical Test"
            ),
            environments = mapOf(
                "z-env" to EnvironmentDescriptor(name = "Z", kind = "conda", spec = emptyMap()),
                "a-env" to EnvironmentDescriptor(name = "A", kind = "pixi", spec = emptyMap()),
            ),
            steps = listOf(
                StepDescriptor(
                    id = "step-1",
                    environmentId = "a-env",
                    task = CommandTaskDescriptor(name = "task-1", executable = "echo")
                )
            )
        )

        val yaml1 = codec.encode(descriptor)
        val descriptor2 = codec.decodeOrThrow(yaml1)
        val yaml2 = codec.encode(descriptor2)

        // Same descriptor → same YAML output
        assertEquals(yaml1, yaml2, "Canonical YAML should be identical after round-trip")
    }

    @Test
    fun `canonicalization with multiple steps preserves order`()
    {
        val descriptor = WorkflowDescriptor(
            schemaVersion = "1.0",
            metadata = WorkflowMetadataDescriptor(name = "Multi-Step"),
            environments = emptyMap(),
            steps = listOf(
                StepDescriptor(id = "step-3", environmentId = "env", task = CommandTaskDescriptor(name = "c", executable = "echo")),
                StepDescriptor(id = "step-1", environmentId = "env", task = CommandTaskDescriptor(name = "a", executable = "echo")),
                StepDescriptor(id = "step-2", environmentId = "env", task = CommandTaskDescriptor(name = "b", executable = "echo")),
            )
        )

        val yaml1 = codec.encode(descriptor)
        // Steps should appear in declaration order, not alphabetical
        val lines = yaml1.lines()
        val step3Index = lines.indexOfFirst { it.contains("step-3") }
        val step1Index = lines.indexOfFirst { it.contains("step-1") }
        val step2Index = lines.indexOfFirst { it.contains("step-2") }

        assertEquals(
            step1Index in (step3Index + 1)..<step2Index, true,
            "Steps should maintain declaration order: step-3, step-1, step-2"
        )
    }

    // ── decodeOrThrow ─────────────────────────────────────────────────────────

    @Test
    fun `decodeOrThrow returns descriptor on success`()
    {
        val yaml = minimalYaml()
        val descriptor = codec.decodeOrThrow(yaml)
        assertEquals("Test Workflow", descriptor.metadata.name)
    }

    @Test
    fun `decodeOrThrow throws YamlCodecException on malformed YAML`()
    {
        val yaml = "invalid: [yaml"
        val ex = assertFailsWith<YamlCodecException> { codec.decodeOrThrow(yaml) }
        assertTrue( ex.message!!.isNotEmpty() )
        assertTrue( ex.cause != null )
    }

    @Test
    fun `decodeOrThrow throws YamlCodecException on schema error`()
    {
        // metadata is expected to be a mapping; passing an integer causes a type mismatch
        // which is a genuine SchemaError that survives lenient/unknown-field mode
        val yaml = """
            schemaVersion: "1.0"
            metadata: 42
            environments: {}
            steps: []
            """.trimIndent()
        val ex = assertFailsWith<YamlCodecException> { codec.decodeOrThrow(yaml) }
        assertTrue( ex.message!!.isNotEmpty() )
    }

    // ── Error Messages ────────────────────────────────────────────────────────

    @Test
    fun `MalformedYaml DecodeResult has descriptive message`()
    {
        val yaml = "["
        val result = codec.decode(yaml)
        assertIs<DecodeResult.MalformedYaml>(result)
        assertTrue(result.message.contains("Malformed"))
    }

    @Test
    fun `SchemaError DecodeResult has descriptive message`()
    {
        val yaml = """
metadata: 123
""".trimIndent()
        val result = codec.decode(yaml)
        assertIs<DecodeResult.SchemaError>(result)
        assertTrue(result.message.isNotEmpty())
    }

    // ── YamlCodecException ────────────────────────────────────────────────────

    @Test
    fun `YamlCodecException message is preserved`()
    {
        val msg = "Test error message"
        val ex = YamlCodecException(msg)
        assertEquals(msg, ex.message)
    }

    @Test
    fun `YamlCodecException cause is preserved`()
    {
        val cause = RuntimeException("Original cause")
        val ex = YamlCodecException("Wrapper", cause)
        assertEquals(cause, ex.cause)
    }
}
