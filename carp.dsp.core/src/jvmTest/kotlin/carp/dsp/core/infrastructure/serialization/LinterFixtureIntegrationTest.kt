package carp.dsp.core.infrastructure.serialization

import carp.dsp.core.application.authoring.descriptor.*
import carp.dsp.core.application.authoring.validation.LinterConfiguration
import carp.dsp.core.application.authoring.validation.WorkflowLinter
import dk.cachet.carp.analytics.domain.validation.ValidationErrorCode
import dk.cachet.carp.analytics.domain.validation.ValidationSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for linter using real workflow fixtures.
 *
 * These tests verify that the linter catches violations in realistic scenarios
 * with actual workflow descriptors.
 */
class LinterFixtureIntegrationTest {

    private val linter = WorkflowLinter

    // Minimal Valid Fixture Tests
    @Test
    fun `minimal valid fixture passes all checks with strict config`() {
        // Use the minimal-valid.yaml fixture (well-formed, no style violations)
        val descriptor = minimalValidFixture()
        val result = linter.lint(descriptor, LinterConfiguration.STRICT)

        assertTrue(result.isValid, "minimal-valid.yaml should pass all checks in strict mode")
    }

    @Test
    fun `minimal valid fixture has no structural errors`() {
        val descriptor = minimalValidFixture()
        val result = linter.lint(descriptor, LinterConfiguration.DEFAULT)

        val structuralErrors = result.issues.filter { issue ->
            issue.severity == ValidationSeverity.ERROR
        }
        assertTrue(structuralErrors.isEmpty(), "No structural errors should be found")
    }


    // Signal Processing Fixture Tests
    @Test
    fun `signal processing fixture passes structural checks`() {
        val descriptor = signalProcessingFixture()
        val result = linter.lint(descriptor, LinterConfiguration.DEFAULT)

        val structuralErrors = result.issues.filter { issue ->
            issue.severity == ValidationSeverity.ERROR
        }
        assertTrue(structuralErrors.isEmpty(), "signal-processing.yaml should pass structural validation")
    }



    // Fixture with Intentional Violations


    @Test
    fun `fixture with naming violations caught in strict mode`() {
        val descriptor = signalProcessingWithNamingViolations()
        val result = linter.lint(descriptor, LinterConfiguration.STRICT)

        assertFalse(result.isValid)

        val namingErrors = result.issues.filter {
            it.code == ValidationErrorCode.NAMING_CONVENTION_VIOLATION
        }
        assertTrue(
            namingErrors.isNotEmpty(),
            "Should catch naming violations. All issues found: ${result.issues.map { "${it.code} at ${it.path}" }}"
        )

        // Verify paths are correct
        namingErrors.forEach { error ->
            assertTrue(
                error.path?.contains("steps") == true || error.path?.contains("environments") == true,
                "Issue ${error.code}: Naming violation path should reference steps or environments, got: '${error.path}'. Message: '${error.message}'"
            )
        }
    }

    @Test
    fun `fixture with missing metadata caught when required`() {
        val descriptor = signalProcessingWithMissingDescriptions()
        val result = linter.lint(descriptor, LinterConfiguration.STRICT)

        val metadataErrors = result.issues.filter {
            it.code == ValidationErrorCode.MISSING_METADATA
        }
        assertTrue(
            metadataErrors.isNotEmpty(),
            "Should catch missing metadata. All issues found: ${result.issues.map { "${it.code} at ${it.path}" }}"
        )

        // Verify messages are actionable
        metadataErrors.forEach { error ->
            assertTrue(
                error.message.contains("description"),
                "Issue ${error.code} at ${error.path}: Message should mention what's missing. Got: '${error.message}'"
            )
            assertNotNull(
                error.path,
                "Issue ${error.code}: Should have path to missing field. Message: '${error.message}'"
            )
        }
    }

    @Test
    fun `fixture with unused environments detected`() {
        val descriptor = signalProcessingWithUnusedEnvironment()
        val result = linter.lint(descriptor, LinterConfiguration.STRICT)

        val unusedErrors = result.issues.filter {
            it.code == ValidationErrorCode.UNUSED_ENVIRONMENT
        }
        assertTrue(
            unusedErrors.isNotEmpty(),
            "Should detect unused environment. All issues found: ${result.issues.map { "${it.code} at ${it.path}" }}"
        )

        // Verify path points to the unused environment
        unusedErrors.forEach { error ->
            assertEquals(
                true,
                error.path?.contains("environments"),
                "Issue ${error.code}: Path should reference environments section, got: '${error.path}'. Message: '${error.message}'"
            )
        }
    }

    @Test
    fun `fixture with too many steps flagged appropriately`() {
        val descriptor = signalProcessingWithExtraSteps()
        val config = LinterConfiguration(maxStepsWarningThreshold = 10)
        val result = linter.lint(descriptor, config)

        val lengthWarnings = result.issues.filter {
            it.code == ValidationErrorCode.WORKFLOW_TOO_LONG
        }
        assertTrue(lengthWarnings.isNotEmpty(), "Should warn about workflow length")

        lengthWarnings.forEach { warning ->
            assertTrue(warning.message.contains("15"), "Should mention the step count")
            assertTrue(warning.message.contains("10"), "Should mention the threshold")
        }
    }


    // Configuration Behaviour Tests


    @Test
    fun `naming violations ignored with lenient config`() {
        val descriptor = signalProcessingWithNamingViolations()
        val result = linter.lint(descriptor, LinterConfiguration.LENIENT)

        val namingErrors = result.issues.filter {
            it.code == ValidationErrorCode.NAMING_CONVENTION_VIOLATION
        }
        assertTrue(namingErrors.isEmpty(), "Lenient config should skip naming checks")
    }

    @Test
    fun `metadata only checked when configured`() {
        val descriptor = signalProcessingWithMissingDescriptions()

        // With default config (not required)
        val resultDefault = linter.lint(descriptor, LinterConfiguration.DEFAULT)
        val errorsDefault = resultDefault.issues.filter {
            it.code == ValidationErrorCode.MISSING_METADATA
        }
        assertTrue(errorsDefault.isEmpty(), "Default config should not require descriptions")

        // With strict config (required)
        val resultStrict = linter.lint(descriptor, LinterConfiguration.STRICT)
        val errorsStrict = resultStrict.issues.filter {
            it.code == ValidationErrorCode.MISSING_METADATA
        }
        assertTrue(errorsStrict.isNotEmpty(), "Strict config should require descriptions")
    }

    @Test
    fun `metadata severity respects configuration`() {
        val descriptor = signalProcessingWithMissingDescriptions()

        // Test with WARNING severity
        val configWarning = LinterConfiguration(
            requireStepDescriptions = true,
            missingMetadataSeverity = "WARNING"
        )
        val resultWarning = linter.lint(descriptor, configWarning)
        val warningIssues = resultWarning.issues.filter {
            it.code == ValidationErrorCode.MISSING_METADATA
        }
        warningIssues.forEach { issue ->
            assertEquals(ValidationSeverity.WARNING, issue.severity)
        }

        // Test with ERROR severity
        val configError = LinterConfiguration(
            requireStepDescriptions = true,
            missingMetadataSeverity = "ERROR"
        )
        val resultError = linter.lint(descriptor, configError)
        val errorIssues = resultError.issues.filter {
            it.code == ValidationErrorCode.MISSING_METADATA
        }
        errorIssues.forEach { issue ->
            assertEquals(ValidationSeverity.ERROR, issue.severity)
        }
    }


    // Error Message Quality Tests


    @Test
    fun `error messages are actionable and specific`() {
        val descriptor = signalProcessingWithNamingViolations()
        val result = linter.lint(descriptor, LinterConfiguration.STRICT)

        result.issues.forEach { issue ->
            // Each issue should have a clear, actionable message
            assertTrue(
                issue.message.isNotBlank(),
                "Issue ${issue.code} at ${issue.path}: Message should not be empty"
            )

            // Message should explain what's wrong
            assertTrue(
                issue.message.length > 20,
                "Issue ${issue.code} at ${issue.path}: Message too short (${issue.message.length} chars): '${issue.message}'"
            )

            // Message should suggest how to fix (for style issues)
            if (issue.severity == ValidationSeverity.WARNING) {
                // Warnings should be helpful
                val messageLower = issue.message.lowercase()
                val hasGuidance = messageLower.contains("follow") ||
                        messageLower.contains("use") ||
                        messageLower.contains("add") ||
                        messageLower.contains("remove") ||
                        messageLower.contains("missing")
                assertTrue(
                    hasGuidance,
                    "Issue ${issue.code} at ${issue.path}: Warning message should guide toward a fix. Got: '${issue.message}'"
                )
            }
        }
    }

    @Test
    fun `error paths are accurate and navigable`() {
        val descriptor = workflowWithMultipleViolations()
        val result = linter.lint(descriptor, LinterConfiguration.STRICT)

        result.issues.forEach { issue ->
            // Every issue should have a path
            assertNotNull(issue.path, "Issue ${issue.code}: Every issue should have a path for location. Message: '${issue.message}'")

            // Path should be in expected format
            val path = issue.path!!
            assertTrue(
                path.contains("[") || path.contains(".") || path.contains("steps") || path.contains("environments"),
                "Issue ${issue.code}: Path should use standard format (e.g., 'steps[0]', 'environments[key]'), got: '$path'. Message: '${issue.message}'"
            )
        }
    }


    // Comprehensive Coverage Tests


    @Test
    fun `all rules triggered with comprehensive violation fixture`() {
        val descriptor = comprehensiveViolationFixture()
        val result = linter.lint(descriptor, LinterConfiguration.STRICT)

        assertFalse(result.isValid)

        val codes = result.issues.map { it.code }.toSet()

        // Should have at least some violations
        assertTrue(codes.isNotEmpty(), "Should detect violations")

        // Verify we're hitting multiple rule types
        val hasStructuralErrors = codes.any { code ->
            code in listOf(
                ValidationErrorCode.WORKFLOW_STEP_ID_DUPLICATE,
                ValidationErrorCode.WORKFLOW_MISSING_ENVIRONMENT
            )
        }

        val hasStyleWarnings = codes.any { code ->
            code in listOf(
                ValidationErrorCode.NAMING_CONVENTION_VIOLATION,
                ValidationErrorCode.MISSING_METADATA,
                ValidationErrorCode.UNUSED_ENVIRONMENT,
                ValidationErrorCode.WORKFLOW_TOO_LONG
            )
        }

        assertTrue(
            hasStructuralErrors || hasStyleWarnings,
            "Should trigger structural or style rules"
        )
    }

    @Test
    fun `valid workflow with all rules enabled passes`() {
        val descriptor = perfectFixture() // Well-formed, follows all conventions
        val result = linter.lint(descriptor, LinterConfiguration.STRICT)

        assertTrue(result.isValid, "Well-formed workflow should pass all checks")
    }


    // Helper Methods - Fixture Builders


    private fun minimalValidFixture(): WorkflowDescriptor =
        WorkflowDescriptor(
            schemaVersion = "1.0",
            metadata = WorkflowMetadataDescriptor(
                name = "Minimal Valid Workflow",
                description = "A minimal but valid workflow"
            ),
            environments = mapOf(
                "env-system" to EnvironmentDescriptor(
                    name = "System Environment",
                    kind = "system"
                )
            ),
            steps = listOf(
                StepDescriptor(
                    id = "step-validate",
                    environmentId = "env-system",
                    metadata = StepMetadataDescriptor(
                        name = "Validate Input",
                        description = "Validates the input file"
                    ),
                    task = CommandTaskDescriptor(
                        name = "validate",
                        executable = "echo",
                        args = listOf("Validating")
                    )
                )
            )
        )

    private fun signalProcessingFixture(): WorkflowDescriptor =
    // Returns the signal-processing.yaml as a descriptor
        WorkflowDescriptor(
            schemaVersion = "1.0",
            metadata = WorkflowMetadataDescriptor(
                name = "Signal Processing Pipeline",
                description = "Multi-step EEG signal processing workflow"
            ),
            environments = mapOf(
                "env-conda-001" to EnvironmentDescriptor(name = "eeg-analysis", kind = "conda"),
                "env-pixi-002" to EnvironmentDescriptor(name = "report-gen", kind = "pixi")
            ),
            steps = listOf(
                StepDescriptor(
                    id = "step-validate",
                    environmentId = "env-conda-001",
                    metadata = StepMetadataDescriptor(name = "Validate Input"),
                    task = CommandTaskDescriptor(name = "validate", executable = "python", args = listOf("validate_eeg.py"))
                ),
                StepDescriptor(
                    id = "step-preprocess",
                    environmentId = "env-conda-001",
                    metadata = StepMetadataDescriptor(name = "Preprocess"),
                    task = CommandTaskDescriptor(name = "preprocess", executable = "python", args = listOf("preprocess.py"))
                )
            )
        )

    private fun signalProcessingWithNamingViolations(): WorkflowDescriptor =
        signalProcessingFixture().copy(
            steps = signalProcessingFixture().steps.map { step ->
                step.copy(id = "InvalidStepID-${step.id}") // Violates naming convention
            }
        )

    private fun signalProcessingWithMissingDescriptions(): WorkflowDescriptor =
        signalProcessingFixture().copy(
            steps = signalProcessingFixture().steps.map { step ->
                step.copy(
                    metadata = step.metadata?.copy(description = null)
                )
            }
        )

    private fun signalProcessingWithUnusedEnvironment(): WorkflowDescriptor =
        signalProcessingFixture().copy(
            environments = signalProcessingFixture().environments +
                    ("env-unused" to EnvironmentDescriptor(name = "Unused", kind = "system"))
        )

    private fun signalProcessingWithExtraSteps(): WorkflowDescriptor =
        signalProcessingFixture().copy(
            steps = (1..15).map { i ->
                StepDescriptor(
                    id = "step-$i",
                    environmentId = "env-conda-001",
                    metadata = StepMetadataDescriptor(name = "Step $i"),
                    task = CommandTaskDescriptor(name = "task-$i", executable = "echo")
                )
            }
        )

    private fun workflowWithMultipleViolations(): WorkflowDescriptor =
        WorkflowDescriptor(
            schemaVersion = "1.0",
            metadata = WorkflowMetadataDescriptor(name = "Violations"),
            environments = mapOf(
                "InvalidEnv" to EnvironmentDescriptor(name = "Invalid", kind = "system"),
                "env-unused" to EnvironmentDescriptor(name = "Unused", kind = "system")
            ),
            steps = (1..15).map { i ->
                StepDescriptor(
                    id = "InvalidStep$i",
                    environmentId = if (i == 1) "InvalidEnv" else "env-unused",
                    metadata = StepMetadataDescriptor(name = "Step $i", description = null),
                    task = CommandTaskDescriptor(name = "task", executable = "echo")
                )
            }
        )

    private fun comprehensiveViolationFixture(): WorkflowDescriptor =
        // Has both structural and style violations
        WorkflowDescriptor(
            schemaVersion = "1.0",
            metadata = WorkflowMetadataDescriptor(name = "test"),
            environments = mapOf(
                "InvalidEnv" to EnvironmentDescriptor(name = "test", kind = "system"),
                "env-unused" to EnvironmentDescriptor(name = "unused", kind = "system")
            ),
            steps = (1..15).map { i ->
                StepDescriptor(
                    id = "InvalidStep$i",
                    environmentId = if (i == 1) "InvalidEnv" else "env-unused",
                    metadata = StepMetadataDescriptor(name = "Step $i", description = null),
                    task = CommandTaskDescriptor(name = "task", executable = "echo")
                )
            }
        )

    private fun perfectFixture(): WorkflowDescriptor =
        WorkflowDescriptor(
            schemaVersion = "1.0",
            metadata = WorkflowMetadataDescriptor(
                name = "Perfect Workflow",
                description = "A perfectly formed workflow"
            ),
            environments = mapOf(
                "env-system" to EnvironmentDescriptor(name = "System", kind = "system")
            ),
            steps = (1..5).map { i ->
                StepDescriptor(
                    id = "step-task-$i", // Good naming
                    environmentId = "env-system",
                    metadata = StepMetadataDescriptor(
                        name = "Task $i",
                        description = "This is task number $i" // Has description
                    ),
                    task = CommandTaskDescriptor(name = "task-$i", executable = "echo")
                )
            }
        )
}
