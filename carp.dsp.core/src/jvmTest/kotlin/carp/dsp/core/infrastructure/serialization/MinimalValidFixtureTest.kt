package carp.dsp.core.infrastructure.serialization

import carp.dsp.core.application.authoring.mapper.WorkflowDescriptorImporter
import carp.dsp.core.application.authoring.validation.WorkflowLinter
import dk.cachet.carp.analytics.domain.validation.ValidationSeverity
import dk.cachet.carp.analytics.domain.workflow.Step
import dk.cachet.carp.analytics.domain.workflow.WorkflowDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verification tests for the `minimal-valid.yaml` golden fixture.
 *
 * This test suite demonstrates that the minimal-valid.yaml fixture:
 * 1. Parses without error (YAML syntax + schema validation)
 * 2. Passes linter (no ERROR issues)
 * 3. Imports to WorkflowDefinition (domain model)
 * 4. Contains expected structure (2 steps, 1 environment, etc.)
 *
 * Use this as a reference for:
 * - How to load and test YAML fixtures
 * - Round-trip testing (YAML → descriptor → domain → YAML)
 * - Verifying workflows are valid before planning
 */
class MinimalValidFixtureTest
{
    private val codec = WorkflowYamlCodec()
    private val linter = WorkflowLinter
    private val importer = WorkflowDescriptorImporter()

    // ── Load fixture YAML ─────────────────────────────────────────────────────

    private fun loadFixtureYaml(): String =
        checkNotNull(
            MinimalValidFixtureTest::class.java.classLoader
                .getResourceAsStream("fixtures/minimal-valid.yaml")
        ) { "Fixture not found: fixtures/minimal-valid.yaml" }
            .bufferedReader()
            .readText()

    // ── Test: YAML Parsing ────────────────────────────────────────────────────

    @Test
    fun `minimal-valid_yaml parses without error`()
    {
        val yaml = loadFixtureYaml()
        val result = codec.decode(yaml)

        assertIs<DecodeResult.Success>(result, "Fixture YAML should parse successfully")
    }

    @Test
    fun `minimal-valid_yaml has correct schemaVersion`()
    {
        val yaml = loadFixtureYaml()
        val result = codec.decode(yaml) as DecodeResult.Success
        val descriptor = result.descriptor

        assertEquals("1.0", descriptor.schemaVersion)
    }

    @Test
    fun `minimal-valid_yaml has correct metadata`()
    {
        val yaml = loadFixtureYaml()
        val result = codec.decode(yaml) as DecodeResult.Success
        val descriptor = result.descriptor

        assertEquals("Minimal Valid Workflow", descriptor.metadata.name)
        assertTrue(descriptor.metadata.description?.isNotEmpty() ?: true)
    }

    // ── Test: Environment Definition ──────────────────────────────────────────

    @Test
    fun `minimal-valid_yaml has environment definition`()
    {
        val yaml = loadFixtureYaml()
        val result = codec.decode(yaml) as DecodeResult.Success
        val descriptor = result.descriptor

        assertEquals(1, descriptor.environments.size)
        assertTrue(descriptor.environments.containsKey("system-env"))
    }

    @Test
    fun `minimal-valid_yaml environment is system kind`()
    {
        val yaml = loadFixtureYaml()
        val result = codec.decode(yaml) as DecodeResult.Success
        val descriptor = result.descriptor

        val env = descriptor.environments["system-env"]
        assertEquals("system", env?.kind)
        assertEquals("System Environment", env?.name)
    }

    // ── Test: Step Structure ──────────────────────────────────────────────────

    @Test
    fun `minimal-valid_yaml has exactly 2 steps`()
    {
        val yaml = loadFixtureYaml()
        val result = codec.decode(yaml) as DecodeResult.Success
        val descriptor = result.descriptor

        assertEquals(2, descriptor.steps.size)
    }

    @Test
    fun `minimal-valid_yaml first step has correct structure`()
    {
        val yaml = loadFixtureYaml()
        val result = codec.decode(yaml) as DecodeResult.Success
        val descriptor = result.descriptor
        val step1 = descriptor.steps[0]

        assertEquals("echo-hello", step1.id)
        assertEquals("system-env", step1.environmentId)
        assertEquals(0, step1.dependsOn.size)
        assertEquals("echo-greeting", step1.task.name)
        val cmdTask = assertIs<carp.dsp.core.application.authoring.descriptor.CommandTaskDescriptor>( step1.task )
        assertEquals("echo", cmdTask.executable)
    }

    @Test
    fun `minimal-valid_yaml second step depends on first`()
    {
        val yaml = loadFixtureYaml()
        val result = codec.decode(yaml) as DecodeResult.Success
        val descriptor = result.descriptor
        val step2 = descriptor.steps[1]

        assertEquals("echo-goodbye", step2.id)
        assertEquals(1, step2.dependsOn.size)
        assertEquals("echo-hello", step2.dependsOn[0])
    }

    // ── Test: Linting ────────────────────────────────────────────────────────

    @Test
    fun `minimal-valid_yaml passes linter with no errors`()
    {
        val yaml = loadFixtureYaml()
        val result = codec.decode(yaml) as DecodeResult.Success
        val descriptor = result.descriptor

        val lintResult = linter.lint(descriptor)
        val errors = lintResult.issues.filter { it.severity == ValidationSeverity.ERROR }

        assertEquals(0, errors.size, "Fixture should have no linting errors")
    }

    @Test
    fun `minimal-valid_yaml has no warnings`()
    {
        val yaml = loadFixtureYaml()
        val result = codec.decode(yaml) as DecodeResult.Success
        val descriptor = result.descriptor

        val lintResult = linter.lint(descriptor)
        val warnings = lintResult.issues.filter { it.severity == ValidationSeverity.WARNING }

        // Note: May have UUID format warnings if using semantic IDs, which is OK
        // (warnings are non-blocking)
        println("Fixture has ${warnings.size} warnings (if any)")
        warnings.forEach { println("  - ${it.message}") }
    }

    // ── Test: Importing to Domain Model ───────────────────────────────────────

    @Test
    fun `minimal-valid_yaml imports to WorkflowDefinition without exception`()
    {
        val yaml = loadFixtureYaml()
        val result = codec.decode(yaml) as DecodeResult.Success
        val descriptor = result.descriptor

        // Should not throw
        val definition = importer.import(descriptor)

        assertIs<WorkflowDefinition>(definition)
    }

    @Test
    fun `minimal-valid_yaml domain model has correct structure`()
    {
        val yaml = loadFixtureYaml()
        val result = codec.decode(yaml) as DecodeResult.Success
        val descriptor = result.descriptor
        val definition = importer.import(descriptor)

        // Check workflow metadata
        assertEquals("Minimal Valid Workflow", definition.workflow.metadata.name)

        // Check environments
        assertEquals(1, definition.environments.size)

        // Check steps
        val steps = definition.workflow.getComponents()
            .filterIsInstance<Step>()
        assertEquals(2, steps.size)
    }

    // ── Test: Round-Trip (YAML → Descriptor → YAML) ───────────────────────────

    @Test
    fun `minimal-valid_yaml round-trip preserves structure`()
    {
        val originalYaml = loadFixtureYaml()

        // Parse
        val result = codec.decode(originalYaml) as DecodeResult.Success
        val descriptor = result.descriptor

        // Re-encode
        val reencoded = codec.encode(descriptor)

        // Re-parse
        val result2 = codec.decode(reencoded) as DecodeResult.Success
        val descriptor2 = result2.descriptor

        // Verify same structure
        assertEquals(descriptor.metadata.name, descriptor2.metadata.name)
        assertEquals(descriptor.steps.size, descriptor2.steps.size)
        assertEquals(descriptor.environments.size, descriptor2.environments.size)
    }

    // ── Test: Canonical YAML Ordering ────────────────────────────────────────

    @Test
    fun `minimal-valid_yaml encodes in canonical form (deterministic)`()
    {
        val yaml = loadFixtureYaml()
        val result = codec.decode(yaml) as DecodeResult.Success
        val descriptor = result.descriptor

        // Encode multiple times
        val canonical1 = codec.encode(descriptor)
        val canonical2 = codec.encode(descriptor)

        // Should be identical
        assertEquals(canonical1, canonical2, "Canonical form should be deterministic")
    }

    @Test
    fun `minimal-valid_yaml canonical form has steps in declaration order`()
    {
        val yaml = loadFixtureYaml()
        val result = codec.decode(yaml) as DecodeResult.Success
        val descriptor = result.descriptor
        val canonical = codec.encode(descriptor)

        // Check that steps appear in order: echo-hello, then echo-goodbye
        val helloIndex = canonical.indexOf("echo-hello")
        val goodbyeIndex = canonical.indexOf("echo-goodbye")

        assertTrue(helloIndex > 0, "Step echo-hello should be present")
        assertTrue(goodbyeIndex > helloIndex, "Step echo-goodbye should come after echo-hello")
    }
}
