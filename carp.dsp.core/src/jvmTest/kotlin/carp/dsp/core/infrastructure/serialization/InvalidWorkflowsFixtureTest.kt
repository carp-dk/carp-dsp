package carp.dsp.core.infrastructure.serialization

import carp.dsp.core.application.authoring.validation.WorkflowLinter
import dk.cachet.carp.analytics.domain.validation.ValidationSeverity
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Verification tests for the `invalid-workflows` fixtures.
 *
 * This test suite demonstrates that the linter correctly detects and reports
 * all types of invalid workflows with clear, actionable error messages.
 *
 * Each test case verifies:
 * 1. YAML parses without error (codec is lenient)
 * 2. Linter detects the specific error
 * 3. Error message is clear and actionable
 * 4. Error includes location information (step ID, field name)
 *
 * Use this as a reference for:
 * - All types of validation errors
 * - Error message quality
 * - Linter effectiveness
 */
class InvalidWorkflowsFixtureTest
{
    private val codec = WorkflowYamlCodec()
    private val linter = WorkflowLinter
    // ── Helper: Invalid workflow examples ─────────────────────────────────────

    private fun duplicateStepIds(): String = """
schemaVersion: "1.0"
metadata:
  name: "Invalid: Duplicate Step IDs"
environments:
  env-1:
    name: "Env"
    kind: "system"
    spec: {}
steps:
  - id: "validate"
    environmentId: "env-1"
    task:
      type: "command"
      name: "task1"
      executable: "echo"
      args: []
  - id: "validate"
    environmentId: "env-1"
    task:
      type: "command"
      name: "task2"
      executable: "echo"
      args: []
    """.trimIndent()

    private fun missingEnvironment(): String = """
schemaVersion: "1.0"
metadata:
  name: "Invalid: Missing Environment"
environments:
  env-1:
    name: "Env"
    kind: "system"
    spec: {}
steps:
  - id: "step-1"
    environmentId: "missing-env"
    task:
      type: "command"
      name: "task"
      executable: "echo"
      args: []
    """.trimIndent()

    private fun unknownEnvironmentKind(): String = """
schemaVersion: "1.0"
metadata:
  name: "Invalid: Unknown Environment Kind"
environments:
  env-1:
    name: "Env"
    kind: "singularity"
    spec: {}
steps:
  - id: "step-1"
    environmentId: "env-1"
    task:
      type: "command"
      name: "task"
      executable: "echo"
      args: []
    """.trimIndent()

    private fun twoStepCycle(): String = """
schemaVersion: "1.0"
metadata:
  name: "Invalid: Two-Step Cycle"
environments:
  env-1:
    name: "Env"
    kind: "system"
    spec: {}
steps:
  - id: "step-1"
    environmentId: "env-1"
    dependsOn:
      - "step-2"
    task:
      type: "command"
      name: "task1"
      executable: "echo"
      args: []
  - id: "step-2"
    environmentId: "env-1"
    dependsOn:
      - "step-1"
    task:
      type: "command"
      name: "task2"
      executable: "echo"
      args: []
    """.trimIndent()

    private fun selfCycle(): String = """
schemaVersion: "1.0"
metadata:
  name: "Invalid: Self-Cycle"
environments:
  env-1:
    name: "Env"
    kind: "system"
    spec: {}
steps:
  - id: "step-1"
    environmentId: "env-1"
    dependsOn:
      - "step-1"
    task:
      type: "command"
      name: "task"
      executable: "echo"
      args: []
    """.trimIndent()

    private fun missingUpstreamStep(): String = """
schemaVersion: "1.0"
metadata:
  name: "Invalid: Missing Upstream Step"
environments:
  env-1:
    name: "Env"
    kind: "system"
    spec: {}
steps:
  - id: "step-1"
    environmentId: "env-1"
    dependsOn:
      - "missing-step"
    task:
      type: "command"
      name: "task"
      executable: "echo"
      args: []
    """.trimIndent()

    private fun duplicateInputPorts(): String = """
schemaVersion: "1.0"
metadata:
  name: "Invalid: Duplicate Input Ports"
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
      name: "task"
      executable: "echo"
      args: []
    inputs:
      - id: "port-1"
        descriptor:
          type: "csv"
      - id: "port-1"
        descriptor:
          type: "csv"
    """.trimIndent()

    private fun duplicateOutputPorts(): String = """
schemaVersion: "1.0"
metadata:
  name: "Invalid: Duplicate Output Ports"
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
      name: "task"
      executable: "echo"
      args: []
    outputs:
      - id: "out-1"
        descriptor:
          type: "csv"
      - id: "out-1"
        descriptor:
          type: "csv"
    """.trimIndent()

    private fun multiStepCycle(): String = """
schemaVersion: "1.0"
metadata:
  name: "Invalid: Multi-Step Cycle"
environments:
  env-1:
    name: "Env"
    kind: "system"
    spec: {}
steps:
  - id: "step-1"
    environmentId: "env-1"
    dependsOn:
      - "step-3"
    task:
      type: "command"
      name: "task1"
      executable: "echo"
      args: []
  - id: "step-2"
    environmentId: "env-1"
    dependsOn:
      - "step-1"
    task:
      type: "command"
      name: "task2"
      executable: "echo"
      args: []
  - id: "step-3"
    environmentId: "env-1"
    dependsOn:
      - "step-2"
    task:
      type: "command"
      name: "task3"
      executable: "echo"
      args: []
    """.trimIndent()

    // ── Test: Duplicate Step IDs ─────────────────────────────────────────────

    @Test
    fun `invalid duplicate step ids detected by linter`()
    {
        val yaml = duplicateStepIds()
        val result = codec.decode(yaml)
        assertIs<DecodeResult.Success>(result)

        val lintResult = linter.lint(result.descriptor)
        val errors = lintResult.issues.filter { it.severity == ValidationSeverity.ERROR && it.message.contains("duplicated") }

        assertTrue(errors.isNotEmpty(), "Should detect duplicate step IDs")
        assertTrue(errors[0].message.contains("validate"), "Error should identify the duplicate ID")
    }

    // ── Test: Missing Environment ────────────────────────────────────────────

    @Test
    fun `invalid missing environment detected by linter`()
    {
        val yaml = missingEnvironment()
        val result = codec.decode(yaml)
        assertIs<DecodeResult.Success>(result)

        val lintResult = linter.lint(result.descriptor)
        val errors = lintResult.issues.filter {
            it.severity == ValidationSeverity.ERROR && it.message.contains("non-existent environment")
        }

        assertTrue(errors.isNotEmpty(), "Should detect missing environment")
        assertTrue(errors[0].message.contains("missing-env"), "Error should name the missing environment")
    }

    // ── Test: Unknown Environment Kind ───────────────────────────────────────

    @Test
    fun `invalid unknown environment kind detected by linter`()
    {
        val yaml = unknownEnvironmentKind()
        val result = codec.decode(yaml)
        assertIs<DecodeResult.Success>(result)

        val lintResult = linter.lint(result.descriptor)
        val warnings = lintResult.issues.filter {
            it.severity == ValidationSeverity.WARNING && it.message.contains("not recognized")
        }

        assertTrue(warnings.isNotEmpty(), "Should warn about unknown environment kind")
        assertTrue(warnings[0].message.contains("singularity"), "Warning should name the unknown kind")
    }

    // ── Test: Two-Step Cycle ─────────────────────────────────────────────────

    @Test
    fun `invalid two step cycle detected by linter`()
    {
        val yaml = twoStepCycle()
        val result = codec.decode(yaml)
        assertIs<DecodeResult.Success>(result)

        val lintResult = linter.lint(result.descriptor)
        val errors = lintResult.issues.filter { it.severity == ValidationSeverity.ERROR && it.message.contains("Circular") }

        assertTrue(errors.isNotEmpty(), "Should detect circular dependency")
    }

    // ── Test: Self-Cycle ────────────────────────────────────────────────────

    @Test
    fun `invalid self cycle detected by linter`()
    {
        val yaml = selfCycle()
        val result = codec.decode(yaml)
        assertIs<DecodeResult.Success>(result)

        val lintResult = linter.lint(result.descriptor)
        val errors = lintResult.issues.filter { it.severity == ValidationSeverity.ERROR && it.message.contains("Circular") }

        assertTrue(errors.isNotEmpty(), "Should detect self-cycle")
    }

    // ── Test: Missing Upstream Step ──────────────────────────────────────────

    @Test
    fun `invalid missing upstream step detected by linter`()
    {
        val yaml = missingUpstreamStep()
        val result = codec.decode(yaml)
        assertIs<DecodeResult.Success>(result)

        val lintResult = linter.lint(result.descriptor)
        val errors = lintResult.issues.filter { it.severity == ValidationSeverity.ERROR && it.message.contains("non-existent") }

        assertTrue(errors.isNotEmpty(), "Should detect missing upstream step")
        assertTrue(errors[0].message.contains("missing-step"), "Error should name the missing step")
    }

    // ── Test: Duplicate Input Ports ──────────────────────────────────────────

    @Test
    fun `invalid duplicate input ports detected by linter`()
    {
        val yaml = duplicateInputPorts()
        val result = codec.decode(yaml)
        assertIs<DecodeResult.Success>(result)

        val lintResult = linter.lint(result.descriptor)
        val errors = lintResult.issues.filter { it.severity == ValidationSeverity.ERROR && it.path?.contains("inputs") == true }

        assertTrue(errors.isNotEmpty(), "Should detect duplicate input ports")
        assertTrue(errors[0].message.contains("port-1"), "Error should identify the duplicate port")
    }

    // ── Test: Duplicate Output Ports ─────────────────────────────────────────

    @Test
    fun `invalid duplicate output ports detected by linter`()
    {
        val yaml = duplicateOutputPorts()
        val result = codec.decode(yaml)
        assertIs<DecodeResult.Success>(result)

        val lintResult = linter.lint(result.descriptor)
        val errors = lintResult.issues.filter {
            it.severity == ValidationSeverity.ERROR && it.path?.contains("outputs") == true
        }

        assertTrue(errors.isNotEmpty(), "Should detect duplicate output ports")
        assertTrue(errors[0].message.contains("out-1"), "Error should identify the duplicate port")
    }

    // ── Test: Multi-Step Cycle ───────────────────────────────────────────────

    @Test
    fun `invalid multi step cycle detected by linter`()
    {
        val yaml = multiStepCycle()
        val result = codec.decode(yaml)
        assertIs<DecodeResult.Success>(result)

        val lintResult = linter.lint(result.descriptor)
        val errors = lintResult.issues.filter { it.severity == ValidationSeverity.ERROR && it.message.contains("Circular") }

        assertTrue(errors.isNotEmpty(), "Should detect multi-step cycle")
    }

    // ── Test: Error Message Quality ──────────────────────────────────────────

    @Test
    fun `invalid error messages are actionable`()
    {
        val yaml = missingEnvironment()
        val result = codec.decode(yaml)
        assertIs<DecodeResult.Success>(result)

        val lintResult = linter.lint(result.descriptor)
        val error = lintResult.issues.find {
            it.severity == ValidationSeverity.ERROR && it.message.contains("non-existent environment")
        }

        assertIs<Any>(error, "Should have error")
        assertTrue(error.message.isNotEmpty(), "Error message should be non-empty")
        assertTrue(error.message.contains("Add environment"), "Error should have actionable guidance in message")
        assertTrue(error.path != null, "Error should specify path")
    }

    // ── Test: Parsing vs Linting ─────────────────────────────────────────────

    @Test
    fun `invalid workflows parse but linter catches errors`()
    {
        val examples = listOf(
            duplicateStepIds(),
            missingEnvironment(),
            twoStepCycle(),
            selfCycle(),
            missingUpstreamStep(),
            duplicateInputPorts(),
            duplicateOutputPorts(),
            multiStepCycle()
        )

        examples.forEach { yaml ->
            // Parse should succeed
            val result = codec.decode(yaml)
            assertIs<DecodeResult.Success>(result, "Even invalid workflows should parse as YAML")

            // But linter should find errors
            val lintResult = linter.lint(result.descriptor)
            val errors = lintResult.issues.filter { it.severity == ValidationSeverity.ERROR }
            assertTrue(errors.isNotEmpty(), "Invalid workflow should have linting errors")
        }
    }

    // ── Test: Fixture Completeness ───────────────────────────────────────────

    @Test
    fun `invalid fixtures cover all linter error types`()
    {
        // Map of error type to test function
        val errorTypes = mapOf(
            "duplicate step IDs" to { duplicateStepIds() },
            "missing environment" to { missingEnvironment() },
            "circular dependency" to { twoStepCycle() },
            "missing upstream step" to { missingUpstreamStep() },
            "duplicate input ports" to { duplicateInputPorts() },
            "duplicate output ports" to { duplicateOutputPorts() }
        )

        // Verify each error type is detected
        errorTypes.forEach { (errorType, fixture) ->
            val yaml = fixture()
            val result = codec.decode(yaml) as DecodeResult.Success
            val lintResult = linter.lint(result.descriptor)
            val errors = lintResult.issues.filter { it.severity == ValidationSeverity.ERROR }

            assertTrue(
                errors.isNotEmpty(),
                "Invalid fixture for '$errorType' should have errors"
            )
        }
    }
}
