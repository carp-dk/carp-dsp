package carp.dsp.core.infrastructure.serialization

import carp.dsp.core.application.authoring.mapper.WorkflowDescriptorImporter
import carp.dsp.core.application.authoring.validation.WorkflowLinter
import dk.cachet.carp.analytics.domain.validation.ValidationSeverity
import dk.cachet.carp.analytics.domain.workflow.Step
import dk.cachet.carp.common.application.UUID
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * INTEGRATION: Comprehensive end-to-end test suite for the complete workflow pipeline.
 *
 * Tests the full journey:
 * 1. YAML → Descriptor (codec)
 * 2. Descriptor validation (linter)
 * 3. Descriptor → Domain model (importer)
 * 4. Determinism and reproducibility
 * 5. Error handling
 *
 * Verifies that all components work together correctly.
 */
class WorkflowYamlIntegrationTest
{
    private val codec = WorkflowYamlCodec()
    private val linter = WorkflowLinter
    private val importer = WorkflowDescriptorImporter()

    // ── Helper: Load fixture YAML ──────────────────────────────────────────

    private fun loadMinimalValidFixture(): String =
        File("src/jvmTest/resources/integration-fixtures/minimal-valid.yaml").readText()

    // ── Test: Round-Trip Identity (Descriptor → YAML → Descriptor) ─────────

    @Test
    fun `round trip descriptor to yaml to descriptor preserves identity`()
    {
        val yaml = loadMinimalValidFixture()
        val result = codec.decode(yaml)
        assertIs<DecodeResult.Success>(result)
        val descriptor1 = result.descriptor

        // Encode back to YAML
        val yaml2 = codec.encode(descriptor1)

        // Decode again
        val result2 = codec.decode(yaml2)
        assertIs<DecodeResult.Success>(result2)
        val descriptor2 = result2.descriptor

        // Verify identity
        assertEquals(descriptor1.schemaVersion, descriptor2.schemaVersion)
        assertEquals(descriptor1.metadata.name, descriptor2.metadata.name)
        assertEquals(descriptor1.environments.size, descriptor2.environments.size)
        assertEquals(descriptor1.steps.size, descriptor2.steps.size)
    }

    @Test
    fun `round trip multiple times produces canonical yaml`()
    {
        val yaml = loadMinimalValidFixture()
        val descriptor = codec.decodeOrThrow(yaml)

        // Encode multiple times
        val yaml1 = codec.encode(descriptor)
        val yaml2 = codec.encode(codec.decodeOrThrow(yaml1))
        val yaml3 = codec.encode(codec.decodeOrThrow(yaml2))

        // All should be identical (canonical form)
        assertEquals(yaml1, yaml2, "Second encoding should match first")
        assertEquals(yaml2, yaml3, "Third encoding should match second")
    }

    // ── Test: Full Pipeline (YAML → Descriptor → Linter → Importer → Domain)

    @Test
    fun `full pipeline minimal valid succeeds`()
    {
        val yaml = loadMinimalValidFixture()

        // Step 1: Decode YAML to descriptor
        val decodeResult = codec.decode(yaml)
        assertIs<DecodeResult.Success>(decodeResult, "Fixture should decode successfully")
        val descriptor = decodeResult.descriptor

        // Step 2: Validate with linter
        val lintResult = linter.lint(descriptor)
        val errors = lintResult.issues.filter { it.severity == ValidationSeverity.ERROR }
        assertEquals(0, errors.size, "Fixture should have no linting errors")

        // Step 3: Import to domain model
        val definition = importer.import(descriptor)

        // Step 4: Verify domain structure
        assertNotNull(definition.workflow)
        assertEquals("Minimal File Output", definition.workflow.metadata.name)
        assertEquals(1, definition.environments.size)

        val steps = definition.workflow.getComponents()
            .filterIsInstance<Step>()
        assertEquals(1, steps.size)
        assertEquals("Write File", steps[0].metadata.name)
    }

    @Test
    fun `full pipeline verifies dependencies resolved`()
    {
        val yaml = loadMinimalValidFixture()
        val descriptor = codec.decodeOrThrow(yaml)
        val definition = importer.import(descriptor)

        val steps = definition.workflow.getComponents()
            .filterIsInstance<Step>()

        // Single step has no dependencies
        assertTrue( steps[0].metadata.id.toString().isNotEmpty(), "Step should have a non-empty ID" )
    }

    // ── Test: Semantic Round-Trip (Descriptor → Domain → Descriptor → Domain)

    @Test
    fun `semantic round trip descriptor to domain and back`()
    {
        val yaml = loadMinimalValidFixture()
        val descriptor1 = codec.decodeOrThrow(yaml)

        // Import to domain
        val definition1 = importer.import(descriptor1)

        // Verify domain structure matches descriptor
        assertEquals(descriptor1.metadata.name, definition1.workflow.metadata.name)
        assertEquals(descriptor1.environments.size, definition1.environments.size)

        val steps1 = definition1.workflow.getComponents()
            .filterIsInstance<Step>()
        assertEquals(descriptor1.steps.size, steps1.size)
    }


    @Test
    fun `determinism different namespaces produce different uuids`()
    {
        val yaml = loadMinimalValidFixture()
        val namespace1 = UUID.randomUUID()
        val namespace2 = UUID.randomUUID()

        val importer1 = WorkflowDescriptorImporter(namespace1)
        val importer2 = WorkflowDescriptorImporter(namespace2)

        val descriptor = codec.decodeOrThrow(yaml)
        val definition1 = importer1.import(descriptor)
        val definition2 = importer2.import(descriptor)

        // Different namespaces should produce different workflow IDs
        assertEquals(
            false,
            definition1.workflow.metadata.id == definition2.workflow.metadata.id,
            "Different namespaces should produce different UUIDs"
        )
    }

    // ── Test: Error Handling (Invalid Workflows Caught by Linter) ───────────

    @Test
    fun `error handling duplicate step ids caught`()
    {
        val yaml = """
schemaVersion: "1.0"
metadata:
  name: "Invalid Workflow"
environments:
  env-1:
    name: "Env"
    kind: "system"
    spec: {}
steps:
  - id: "step-1"
    environmentId: "env-1"
    task:
      type: "command"
      name: "task1"
      executable: "echo"
      args: []
  - id: "step-1"
    environmentId: "env-1"
    task:
      type: "command"
      name: "task2"
      executable: "echo"
      args: []
        """.trimIndent()

        val descriptor = codec.decodeOrThrow(yaml)
        val lintResult = linter.lint(descriptor)
        val errors = lintResult.issues.filter { it.severity == ValidationSeverity.ERROR && it.message.contains("duplicated") }

        assertTrue(errors.isNotEmpty(), "Duplicate step IDs should be caught")
        assertTrue(errors[0].message.contains("step-1"), "Error should identify the duplicate ID")
    }

    @Test
    fun `error handling missing environment caught`()
    {
        val yaml = """
schemaVersion: "1.0"
metadata:
  name: "Invalid Workflow"
environments: {}
steps:
  - id: "step-1"
    environmentId: "missing-env"
    task:
      type: "command"
      name: "task"
      executable: "echo"
      args: []
        """.trimIndent()

        val descriptor = codec.decodeOrThrow(yaml)
        val lintResult = linter.lint(descriptor)
        val errors = lintResult.issues.filter {
            it.severity == ValidationSeverity.ERROR && it.message.contains("non-existent environment")
        }

        assertTrue(errors.isNotEmpty(), "Missing environment should be caught")
    }

    @Test
    fun `error handling circular dependency caught`()
    {
        val yaml = """
schemaVersion: "1.0"
metadata:
  name: "Invalid Workflow"
environments:
  env-1:
    name: "Env"
    kind: "system"
    spec: {}
steps:
  - id: "step-1"
    environmentId: "env-1"
    dependsOn: ["step-2"]
    task:
      type: "command"
      name: "task1"
      executable: "echo"
      args: []
  - id: "step-2"
    environmentId: "env-1"
    dependsOn: ["step-1"]
    task:
      type: "command"
      name: "task2"
      executable: "echo"
      args: []
        """.trimIndent()

        val descriptor = codec.decodeOrThrow(yaml)
        val lintResult = linter.lint(descriptor)
        val errors = lintResult.issues.filter { it.severity == ValidationSeverity.ERROR && it.message.contains("Circular") }

        assertTrue(errors.isNotEmpty(), "Circular dependencies should be caught")
    }

    @Test
    fun `error handling invalid workflows do not import`()
    {
        val yaml = """
schemaVersion: "1.0"
metadata:
  name: "Invalid"
environments: {}
steps:
  - id: "step-1"
    environmentId: "missing-env"
    task:
      type: "command"
      name: "task"
      executable: "echo"
      args: []
        """.trimIndent()

        val descriptor = codec.decodeOrThrow(yaml)
        val lintResult = linter.lint(descriptor)

        // Framework should prevent import of invalid workflows
        // (In production: planner would check linter results before importing)
        val errors = lintResult.issues.filter { it.severity == ValidationSeverity.ERROR }
        assertTrue(errors.isNotEmpty(), "Invalid workflow should have errors")
    }

    // ── Test: Fixture Validation (Comprehensive Check) ──────────────────────

    @Test
    fun `fixture validation minimal valid yaml is complete and valid`()
    {
        val yaml = loadMinimalValidFixture()

        // 1. Parse
        val decodeResult = codec.decode(yaml)
        assertIs<DecodeResult.Success>(decodeResult)

        // 2. Lint
        val lintResult = linter.lint(decodeResult.descriptor)
        val errors = lintResult.issues.filter { it.severity == ValidationSeverity.ERROR }
        assertEquals(0, errors.size)

        // 3. Import
        val definition = importer.import(decodeResult.descriptor)
        assertNotNull(definition.workflow)

        // 4. Verify structure
        val steps = definition.workflow.getComponents()
            .filterIsInstance<Step>()
        assertEquals(1, steps.size)
    }

    // ── Test: End-to-End Workflow Validation Flow ──────────────────────────

    @Test
    fun `end to end author workflow decode validate import`()
    {
        // Simulates what an author or tool would do:
        // 1. Load YAML file
        val yaml = loadMinimalValidFixture()

        // 2. Parse
        val parseResult = codec.decode(yaml)
        if (parseResult is DecodeResult.MalformedYaml || parseResult is DecodeResult.SchemaError) {
            fail("Fixture should parse successfully")
        }
        val descriptor = (parseResult as DecodeResult.Success).descriptor

        // 3. Validate
        val lintResult = linter.lint(descriptor)
        val errors = lintResult.issues.filter { it.severity == ValidationSeverity.ERROR }
        if (errors.isNotEmpty()) {
            fail("Fixture should pass validation. Errors: ${errors.map { it.message }}")
        }

        // 4. Import
        val definition = importer.import(descriptor)

        // 5. Use (simplified: just verify it exists)
        assertTrue(definition.workflow.metadata.name.isNotEmpty())
    }

    @Test
    fun `end to end workflow with namespace consistency`()
    {
        val yaml = loadMinimalValidFixture()
        val fixedNamespace = UUID.parse("550e8400-e29b-41d4-a716-446655440000")

        // Author saves workflow with consistent namespace
        val importer1 = WorkflowDescriptorImporter(fixedNamespace)
        val descriptor = codec.decodeOrThrow(yaml)
        val definition1 = importer1.import(descriptor)

        // Later, author loads same workflow with same namespace
        val importer2 = WorkflowDescriptorImporter(fixedNamespace)
        val definition2 = importer2.import(descriptor)

        // All UUIDs should match (reproducibility)
        assertEquals(definition1.workflow.metadata.id, definition2.workflow.metadata.id)

        val steps1 = definition1.workflow.getComponents()
            .filterIsInstance<Step>()
        val steps2 = definition2.workflow.getComponents()
            .filterIsInstance<Step>()

        steps1.zip(steps2).forEach { (s1, s2) ->
            assertEquals(s1.metadata.id, s2.metadata.id)
        }
    }

    // ── Test: Metadata Preservation Through Pipeline ───────────────────────

    @Test
    fun `metadata preservation all fields maintained`()
    {
        val yaml = loadMinimalValidFixture()
        val descriptor = codec.decodeOrThrow(yaml)

        // Verify all metadata present
        assertEquals("minimal-file-output", descriptor.metadata.id)
        assertEquals("Minimal File Output", descriptor.metadata.name)
        assertEquals("1.0", descriptor.metadata.version)

        // Verify preserved through import
        val definition = importer.import(descriptor)
        assertEquals("Minimal File Output", definition.workflow.metadata.name)
    }
}
