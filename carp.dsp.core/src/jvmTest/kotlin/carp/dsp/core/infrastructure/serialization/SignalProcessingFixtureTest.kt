package carp.dsp.core.infrastructure.serialization

import carp.dsp.core.application.authoring.descriptor.CommandTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.PythonTaskDescriptor
import carp.dsp.core.application.authoring.mapper.WorkflowDescriptorImporter
import carp.dsp.core.application.authoring.validation.WorkflowLinter
import dk.cachet.carp.analytics.domain.validation.ValidationSeverity
import dk.cachet.carp.analytics.domain.workflow.Step
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verification tests for the `signal-processing.yaml` golden fixture.
 *
 * This test suite demonstrates that the signal-processing.yaml fixture:
 * 1. Parses without error
 * 2. Passes linter (no ERROR issues)
 * 3. Imports to WorkflowDefinition
 * 4. Contains complex structure (4 steps, dependencies, input/output refs)
 * 5. All input/output references resolve correctly
 * 6. Dependency graph is acyclic (valid DAG)
 *
 * Use this as a reference for:
 * - Complex workflow structure
 * - Mixed task types (command + python)
 * - Step dependency chains
 * - Multiple input/output ports
 */
class SignalProcessingFixtureTest
{
    private val codec = WorkflowYamlCodec()
    private val linter = WorkflowLinter
    private val importer = WorkflowDescriptorImporter()

    private fun loadSignalProcessingFixture(): String =
        File("src/jvmTest/resources/fixtures/signal-processing.yaml").readText()

    // ── Test: Parse ──────────────────────────────────────────────────────────

    @Test
    fun `signal processing yaml parses without error`()
    {
        val yaml = loadSignalProcessingFixture()
        val result = codec.decode(yaml)
        assertIs<DecodeResult.Success>(result, "Fixture should parse successfully")
    }

    // ── Test: Structure ──────────────────────────────────────────────────────

    @Test
    fun `signal processing yaml has correct structure`()
    {
        val yaml = loadSignalProcessingFixture()
        val result = codec.decode(yaml) as DecodeResult.Success
        val descriptor = result.descriptor

        // Check metadata
        assertEquals("EEG Signal Processing Pipeline", descriptor.metadata.name)

        // Check environments
        assertEquals(2, descriptor.environments.size)
        assertTrue(descriptor.environments.containsKey("conda-eeg"))
        assertTrue(descriptor.environments.containsKey("report-env"))

        // Check steps
        assertEquals(4, descriptor.steps.size)
        assertEquals("validate-input", descriptor.steps[0].id)
        assertEquals("preprocess-eeg", descriptor.steps[1].id)
        assertEquals("extract-features", descriptor.steps[2].id)
        assertEquals("generate-report", descriptor.steps[3].id)
    }

    // ── Test: Task Types ─────────────────────────────────────────────────────

    @Test
    fun `signal processing yaml has mixed task types`()
    {
        val yaml = loadSignalProcessingFixture()
        val result = codec.decode(yaml) as DecodeResult.Success
        val descriptor = result.descriptor

        assertIs<CommandTaskDescriptor>( descriptor.steps[0].task ) // validate-input
        assertIs<PythonTaskDescriptor>( descriptor.steps[1].task ) // preprocess-eeg
        assertIs<PythonTaskDescriptor>( descriptor.steps[2].task ) // extract-features
        assertIs<CommandTaskDescriptor>( descriptor.steps[3].task ) // generate-report
    }

    // ── Test: Dependencies ───────────────────────────────────────────────────

    @Test
    fun `signal processing yaml has correct dependency chain`()
    {
        val yaml = loadSignalProcessingFixture()
        val result = codec.decode(yaml) as DecodeResult.Success
        val descriptor = result.descriptor

        // Step 1: no dependencies
        assertEquals(0, descriptor.steps[0].dependsOn.size)

        // Step 2: depends on step 1
        assertEquals(1, descriptor.steps[1].dependsOn.size)
        assertEquals("validate-input", descriptor.steps[1].dependsOn[0])

        // Step 3: depends on step 2
        assertEquals(1, descriptor.steps[2].dependsOn.size)
        assertEquals("preprocess-eeg", descriptor.steps[2].dependsOn[0])

        // Step 4: depends on step 3
        assertEquals(1, descriptor.steps[3].dependsOn.size)
        assertEquals("extract-features", descriptor.steps[3].dependsOn[0])
    }

    // ── Test: Input/Output Ports ─────────────────────────────────────────────

    @Test
    fun `signal processing yaml has input and output ports`()
    {
        val yaml = loadSignalProcessingFixture()
        val result = codec.decode(yaml) as DecodeResult.Success
        val descriptor = result.descriptor

        // Step 1: 1 input, 1 output
        assertEquals(1, descriptor.steps[0].inputs.size)
        assertEquals(1, descriptor.steps[0].outputs.size)

        // Step 2: 1 input, 1 output
        assertEquals(1, descriptor.steps[1].inputs.size)
        assertEquals(1, descriptor.steps[1].outputs.size)

        // Step 3: 1 input, 1 output
        assertEquals(1, descriptor.steps[2].inputs.size)
        assertEquals(1, descriptor.steps[2].outputs.size)

        // Step 4: 1 input, 1 output
        assertEquals(1, descriptor.steps[3].inputs.size)
        assertEquals(1, descriptor.steps[3].outputs.size)
    }

    // ── Test: Arguments with References ──────────────────────────────────────

    @Test
    fun `signal processing yaml has arguments with references`()
    {
        val yaml = loadSignalProcessingFixture()
        val result = codec.decode(yaml) as DecodeResult.Success
        val descriptor = result.descriptor

        // Step 1: args with literal and input-ref
        val step1Args = assertIs<CommandTaskDescriptor>( descriptor.steps[0].task ).args
        assertTrue(step1Args.isNotEmpty(), "Step 1 should have arguments")

        // Step 2: args with input-ref, output-ref, and literals
        val step2Args = assertIs<PythonTaskDescriptor>( descriptor.steps[1].task ).args
        assertTrue(step2Args.isNotEmpty(), "Step 2 should have arguments")

        // Step 3: args with input-ref and output-ref
        val step3Args = assertIs<PythonTaskDescriptor>( descriptor.steps[2].task ).args
        assertTrue(step3Args.isNotEmpty(), "Step 3 should have arguments")

        // Step 4: args with literals and references
        val step4Args = assertIs<CommandTaskDescriptor>( descriptor.steps[3].task ).args
        assertTrue(step4Args.isNotEmpty(), "Step 4 should have arguments")
    }

    // ── Test: Linting ────────────────────────────────────────────────────────

    @Test
    fun `signal processing yaml passes linter with no errors`()
    {
        val yaml = loadSignalProcessingFixture()
        val result = codec.decode(yaml) as DecodeResult.Success
        val descriptor = result.descriptor

        val lintResult = linter.lint(descriptor)
        val errors = lintResult.issues.filter { it.severity == ValidationSeverity.ERROR }

        assertEquals(0, errors.size, "Fixture should have no linting errors")
    }

    @Test
    fun `signal processing yaml has valid dependency dag`()
    {
        val yaml = loadSignalProcessingFixture()
        val result = codec.decode(yaml) as DecodeResult.Success
        val descriptor = result.descriptor

        val lintResult = linter.lint(descriptor)
        val cycleErrors = lintResult.issues.filter {
            it.severity == ValidationSeverity.ERROR && it.message.contains("Circular")
        }

        assertEquals(0, cycleErrors.size, "Fixture should have no circular dependencies")
    }

    // ── Test: Importing ─────────────────────────────────────────────────────

    @Test
    fun `signal processing yaml imports to domain model`()
    {
        val yaml = loadSignalProcessingFixture()
        val descriptor = codec.decodeOrThrow(yaml)

        // Should not throw
        val definition = importer.import(descriptor)

        assertNotNull(definition.workflow)
        assertEquals("EEG Signal Processing Pipeline", definition.workflow.metadata.name)
    }

    @Test
    fun `signal processing yaml domain model has correct step count`()
    {
        val yaml = loadSignalProcessingFixture()
        val descriptor = codec.decodeOrThrow(yaml)
        val definition = importer.import(descriptor)

        val steps = definition.workflow.getComponents()
            .filterIsInstance<Step>()

        assertEquals(4, steps.size, "Domain model should have 4 steps")
    }

    @Test
    fun `signal processing yaml domain model has two environments`()
    {
        val yaml = loadSignalProcessingFixture()
        val descriptor = codec.decodeOrThrow(yaml)
        val definition = importer.import(descriptor)

        assertEquals(2, definition.environments.size, "Domain model should have 2 environments")
    }

    // ── Test: End-to-End Validation ──────────────────────────────────────────

    @Test
    fun `signal processing yaml complete validation pipeline`()
    {
        val yaml = loadSignalProcessingFixture()

        // Parse
        val decodeResult = codec.decode(yaml)
        assertIs<DecodeResult.Success>(decodeResult)
        val descriptor = decodeResult.descriptor

        // Lint
        val lintResult = linter.lint(descriptor)
        val errors = lintResult.issues.filter { it.severity == ValidationSeverity.ERROR }
        assertEquals(0, errors.size, "Should have no errors")

        // Import
        val definition = importer.import(descriptor)
        assertNotNull(definition.workflow)

        // Verify structure
        val steps = definition.workflow.getComponents()
            .filterIsInstance<Step>()
        assertEquals(4, steps.size)
        assertEquals(2, definition.environments.size)
    }
}
