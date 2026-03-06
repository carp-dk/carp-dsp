package carp.dsp.core.application.authoring.validation

import carp.dsp.core.application.authoring.descriptor.CommandTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.DataPortDescriptor
import carp.dsp.core.application.authoring.descriptor.EnvironmentDescriptor
import carp.dsp.core.application.authoring.descriptor.StepDescriptor
import carp.dsp.core.application.authoring.descriptor.StepMetadataDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowMetadataDescriptor
import dk.cachet.carp.analytics.domain.validation.ValidationErrorCode
import dk.cachet.carp.analytics.domain.validation.ValidationSeverity
import dk.cachet.carp.common.application.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Suppress("LargeClass")
class WorkflowLinterTest
{
    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun minimalWorkflow(
        steps: List<StepDescriptor> = emptyList(),
        environments: Map<String, EnvironmentDescriptor> = emptyMap(),
    ) = WorkflowDescriptor(
        schemaVersion = "1.0",
        metadata = WorkflowMetadataDescriptor(name = "Test Workflow"),
        environments = environments,
        steps = steps
    )

    private fun minimalStep(
        id: String = UUID.randomUUID().toString(),
        environmentId: String = "env-1",
        dependsOn: List<String> = emptyList(),
        inputs: List<DataPortDescriptor> = emptyList(),
        outputs: List<DataPortDescriptor> = emptyList(),
    ) = StepDescriptor(
        id = id,
        environmentId = environmentId,
        dependsOn = dependsOn,
        task = CommandTaskDescriptor(name = "task", executable = "echo"),
        inputs = inputs,
        outputs = outputs
    )

    private fun minimalEnvironment(
        name: String = "Test Env",
        kind: String = "conda"
    ) = EnvironmentDescriptor(name = name, kind = kind, spec = emptyMap())

    // ── Test: Valid Workflow Passes ───────────────────────────────────────────

    @Test
    fun `lint empty workflow returns valid workflow`()
    {
        val workflow = minimalWorkflow()
        val result = WorkflowLinter.lint(workflow)
        assertTrue(result.isValid)
    }

    @Test
    fun `lint valid single-step workflow returns no issues`()
    {
        val workflow = minimalWorkflow(
            steps = listOf(minimalStep()),
            environments = mapOf("env-1" to minimalEnvironment())
        )
        val result = WorkflowLinter.lint(workflow)
        assertTrue(result.isValid)
        assertEquals(0, result.issues.size)
    }

    @Test
    fun `lint valid multi-step workflow with dependencies returns no issues`()
    {
        val step1Id = UUID.randomUUID().toString()
        val step2Id = UUID.randomUUID().toString()
        val step3Id = UUID.randomUUID().toString()
        val workflow = minimalWorkflow(
            steps = listOf(
                minimalStep(id = step1Id),
                minimalStep(id = step2Id, dependsOn = listOf(step1Id)),
                minimalStep(id = step3Id, dependsOn = listOf(step2Id))
            ),
            environments = mapOf("env-1" to minimalEnvironment())
        )
        val result = WorkflowLinter.lint(workflow)
        assertTrue(result.isValid)
        assertEquals(0, result.issues.size)
    }

    // ── Check 1: Duplicate Step IDs ───────────────────────────────────────────

    @Test
    fun `lint detects duplicate step IDs`()
    {
        val workflow = minimalWorkflow(
            steps = listOf(
                minimalStep(id = "step-1"),
                minimalStep(id = "step-1") // Duplicate
            ),
            environments = mapOf("env-1" to minimalEnvironment())
        )
        val result = WorkflowLinter.lint(workflow)
        val errors = result.issues.filter { it.severity == ValidationSeverity.ERROR && it.message.contains("duplicated") }
        assertEquals(1, errors.size)
        assertTrue(errors[0].message.contains("step-1"))
        assertTrue(errors[0].message.contains("2 times"))
        assertEquals(ValidationErrorCode.WORKFLOW_STEP_ID_DUPLICATE, errors[0].code)
    }

    @Test
    fun `lint detects multiple duplicate step IDs`()
    {
        val workflow = minimalWorkflow(
            steps = listOf(
                minimalStep(id = "step-1"),
                minimalStep(id = "step-1"),
                minimalStep(id = "step-2"),
                minimalStep(id = "step-2")
            ),
            environments = mapOf("env-1" to minimalEnvironment())
        )
        val result = WorkflowLinter.lint(workflow)
        val errors = result.issues.filter { it.severity == ValidationSeverity.ERROR && it.message.contains("duplicated") }
        assertEquals(2, errors.size)
    }

    // ── Check 2: Duplicate Input Port IDs ────────────────────────────────────

    @Test
    fun `lint detects duplicate input port IDs within step`()
    {
        val workflow = minimalWorkflow(
            steps = listOf(
                minimalStep(
                    id = "step-1",
                    inputs = listOf(
                        DataPortDescriptor(id = "port-1"),
                        DataPortDescriptor(id = "port-1") // Duplicate
                    )
                )
            ),
            environments = mapOf("env-1" to minimalEnvironment())
        )
        val result = WorkflowLinter.lint(workflow)
        val errors = result.issues.filter { it.severity == ValidationSeverity.ERROR && it.path?.contains("inputs") == true }
        assertEquals(1, errors.size)
        assertTrue(errors[0].message.contains("port-1"))
        assertEquals(ValidationErrorCode.STEP_INPUT_PORT_DUPLICATE_ID, errors[0].code)
    }

    // ── Check 3: Duplicate Output Port IDs ───────────────────────────────────

    @Test
    fun `lint detects duplicate output port IDs within step`()
    {
        val workflow = minimalWorkflow(
            steps = listOf(
                minimalStep(
                    id = "step-1",
                    outputs = listOf(
                        DataPortDescriptor(id = "out-1"),
                        DataPortDescriptor(id = "out-1") // Duplicate
                    )
                )
            ),
            environments = mapOf("env-1" to minimalEnvironment())
        )
        val result = WorkflowLinter.lint(workflow)
        val errors = result.issues.filter { it.severity == ValidationSeverity.ERROR && it.path?.contains("outputs") == true }
        assertEquals(1, errors.size)
        assertTrue(errors[0].message.contains("out-1"))
        assertEquals(ValidationErrorCode.STEP_OUTPUT_PORT_DUPLICATE_ID, errors[0].code)
    }

    // ── Check 4: Step References Non-Existent Environment ────────────────────

    @Test
    fun `lint detects step referencing non-existent environment`()
    {
        val workflow = minimalWorkflow(
            steps = listOf(
                minimalStep(id = "step-1", environmentId = "missing-env")
            ),
            environments = mapOf("env-1" to minimalEnvironment())
        )
        val result = WorkflowLinter.lint(workflow)
        val errors = result.issues.filter {
            it.severity == ValidationSeverity.ERROR && it.message.contains("non-existent environment")
        }
        assertEquals(1, errors.size)
        assertTrue(errors[0].message.contains("missing-env"))
        assertEquals(ValidationErrorCode.WORKFLOW_MISSING_ENVIRONMENT, errors[0].code)
    }

    // ── Check 5: Unknown Environment Kind ─────────────────────────────────────

    @Test
    fun `lint warns on unknown environment kind`()
    {
        val workflow = minimalWorkflow(
            steps = listOf(minimalStep()),
            environments = mapOf(
                "env-1" to EnvironmentDescriptor(name = "Test", kind = "docker", spec = emptyMap())
            )
        )
        val result = WorkflowLinter.lint(workflow)
        val warnings = result.issues.filter {
            it.severity == ValidationSeverity.WARNING && it.message.contains("not recognized")
        }
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].message.contains("docker"))
        assertEquals(ValidationErrorCode.WORKFLOW_UNKNOWN_ENV_KIND, warnings[0].code)
    }

    @Test
    fun `lint accepts supported environment kinds`()
    {
        val supportedKinds = listOf("conda", "pixi", "python", "system")
        supportedKinds.forEach { kind ->
            val workflow = minimalWorkflow(
                steps = listOf(minimalStep(environmentId = "env-1")),
                environments = mapOf(
                    "env-1" to EnvironmentDescriptor(name = "Test", kind = kind, spec = emptyMap())
                )
            )
            val result = WorkflowLinter.lint(workflow)
            val kindWarnings = result.issues.filter {
                it.severity == ValidationSeverity.WARNING && it.message.contains("not recognized")
            }
            assertEquals(0, kindWarnings.size, "Environment kind '$kind' should be recognized")
        }
    }

    @Test
    fun `lint is case-insensitive for environment kind`()
    {
        val workflow = minimalWorkflow(
            steps = listOf(minimalStep()),
            environments = mapOf(
                "env-1" to EnvironmentDescriptor(name = "Test", kind = "CONDA", spec = emptyMap())
            )
        )
        val result = WorkflowLinter.lint(workflow)
        val kindWarnings = result.issues.filter {
            it.severity == ValidationSeverity.WARNING && it.message.contains("not recognized")
        }
        assertEquals(0, kindWarnings.size, "Environment kind should be case-insensitive")
    }

    // ── Check 6: UUID Format Validation ───────────────────────────────────────

    @Test
    fun `lint warns on non-UUID step ID`()
    {
        val workflow = minimalWorkflow(
            steps = listOf(
                minimalStep(id = "step-1") // Not a UUID
            ),
            environments = mapOf("env-1" to minimalEnvironment())
        )
        val result = WorkflowLinter.lint(workflow)
        val warnings = result.issues.filter { it.severity == ValidationSeverity.WARNING && it.path?.contains("id") == true }
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].message.contains("not a valid UUID"))
        assertEquals(ValidationErrorCode.INVALID_UUID_FORMAT, warnings[0].code)
    }

    @Test
    fun `lint warns on non-UUID task ID`()
    {
        val workflow = minimalWorkflow(
            steps = listOf(
                StepDescriptor(
                    id = "550e8400-e29b-41d4-a716-446655440000",
                    environmentId = "env-1",
                    task = CommandTaskDescriptor(
                        id = "task-001", // Not a UUID
                        name = "task",
                        executable = "echo"
                    )
                )
            ),
            environments = mapOf("env-1" to minimalEnvironment())
        )
        val result = WorkflowLinter.lint(workflow)
        val warnings = result.issues.filter {
            it.severity == ValidationSeverity.WARNING && it.path?.contains("task.id") == true
        }
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].message.contains("not a valid UUID"))
        assertEquals(ValidationErrorCode.INVALID_UUID_FORMAT, warnings[0].code)
    }

    @Test
    fun `lint accepts valid UUID format`()
    {
        val validUuid = "550e8400-e29b-41d4-a716-446655440000"
        val workflow = minimalWorkflow(
            steps = listOf(
                minimalStep(id = validUuid)
            ),
            environments = mapOf("env-1" to minimalEnvironment())
        )
        val result = WorkflowLinter.lint(workflow)
        val uuidWarnings = result.issues.filter {
            it.severity == ValidationSeverity.WARNING && it.message.contains("not a valid UUID")
        }
        assertEquals(0, uuidWarnings.size)
    }

    // ── Check 7: Step Depends on Non-Existent Upstream Step ───────────────────

    @Test
    fun `lint detects step depending on non-existent upstream step`()
    {
        val workflow = minimalWorkflow(
            steps = listOf(
                minimalStep(id = "step-1", dependsOn = listOf("missing-step"))
            ),
            environments = mapOf("env-1" to minimalEnvironment())
        )
        val result = WorkflowLinter.lint(workflow)
        val errors = result.issues.filter { it.severity == ValidationSeverity.ERROR && it.message.contains("non-existent") }
        assertEquals(1, errors.size)
        assertTrue(errors[0].message.contains("missing-step"))
        assertEquals(ValidationErrorCode.WORKFLOW_DEP_REFERENCE_MISSING, errors[0].code)
    }

    @Test
    fun `lint detects multiple missing upstream references`()
    {
        val workflow = minimalWorkflow(
            steps = listOf(
                minimalStep(id = "step-1", dependsOn = listOf("missing-1", "missing-2"))
            ),
            environments = mapOf("env-1" to minimalEnvironment())
        )
        val result = WorkflowLinter.lint(workflow)
        val errors = result.issues.filter { it.severity == ValidationSeverity.ERROR && it.message.contains("non-existent") }
        assertEquals(2, errors.size)
    }

    // ── Check 8: Cycle in Step Dependencies ───────────────────────────────────

    @Test
    fun `lint detects self-cycle (step depends on itself)`()
    {
        val workflow = minimalWorkflow(
            steps = listOf(
                minimalStep(id = "step-1", dependsOn = listOf("step-1"))
            ),
            environments = mapOf("env-1" to minimalEnvironment())
        )
        val result = WorkflowLinter.lint(workflow)
        val errors = result.issues.filter { it.severity == ValidationSeverity.ERROR && it.message.contains("Circular") }
        assertEquals(1, errors.size)
        assertEquals(ValidationErrorCode.WORKFLOW_DEP_CYCLE_DETECTED, errors[0].code)
    }

    @Test
    fun `lint detects two-step cycle`()
    {
        val workflow = minimalWorkflow(
            steps = listOf(
                minimalStep(id = "step-1", dependsOn = listOf("step-2")),
                minimalStep(id = "step-2", dependsOn = listOf("step-1"))
            ),
            environments = mapOf("env-1" to minimalEnvironment())
        )
        val result = WorkflowLinter.lint(workflow)
        val errors = result.issues.filter { it.severity == ValidationSeverity.ERROR && it.message.contains("Circular") }
        // Both steps are part of the cycle
        assertTrue(errors.isNotEmpty(), "Should detect cycle")
    }

    @Test
    fun `lint detects multi-step cycle`()
    {
        val workflow = minimalWorkflow(
            steps = listOf(
                minimalStep(id = "step-1", dependsOn = listOf("step-3")),
                minimalStep(id = "step-2", dependsOn = listOf("step-1")),
                minimalStep(id = "step-3", dependsOn = listOf("step-2"))
            ),
            environments = mapOf("env-1" to minimalEnvironment())
        )
        val result = WorkflowLinter.lint(workflow)
        val errors = result.issues.filter { it.severity == ValidationSeverity.ERROR && it.message.contains("Circular") }
        assertTrue(errors.isNotEmpty(), "Should detect cycle")
    }

    @Test
    fun `lint allows linear dependencies without cycle`()
    {
        val workflow = minimalWorkflow(
            steps = listOf(
                minimalStep(id = "step-1"),
                minimalStep(id = "step-2", dependsOn = listOf("step-1")),
                minimalStep(id = "step-3", dependsOn = listOf("step-2")),
                minimalStep(id = "step-4", dependsOn = listOf("step-3"))
            ),
            environments = mapOf("env-1" to minimalEnvironment())
        )
        val result = WorkflowLinter.lint(workflow)
        val cycles = result.issues.filter { it.severity == ValidationSeverity.ERROR && it.message.contains("Circular") }
        assertEquals(0, cycles.size)
    }

    // ── Combined Error Cases ──────────────────────────────────────────────────

    @Test
    fun `lint catches multiple independent errors in same workflow`()
    {
        val workflow = minimalWorkflow(
            steps = listOf(
                minimalStep(id = "step-1", dependsOn = listOf("missing-step")),
                minimalStep(id = "step-1") // Duplicate ID
            ),
            environments = mapOf("env-1" to minimalEnvironment())
        )
        val result = WorkflowLinter.lint(workflow)
        val errors = result.issues.filter { it.severity == ValidationSeverity.ERROR }
        // Should catch: duplicate step ID + missing upstream reference
        assertTrue(errors.size >= 2, "Should catch multiple errors")
    }

    // ── Issue Properties ──────────────────────────────────────────────────────

    @Test
    fun `lint issue has correct path and subjectId information`()
    {
        val workflow = minimalWorkflow(
            steps = listOf(
                minimalStep(id = "step-1", environmentId = "missing-env")
            ),
            environments = emptyMap()
        )
        val result = WorkflowLinter.lint(workflow)
        val error = result.issues.find { it.message.contains("non-existent environment") }

        assertEquals("step-1", error?.subjectId)
        assertEquals(error?.path?.contains("environmentId"), true)
    }

    @Test
    fun `lint issue has informative message`()
    {
        val workflow = minimalWorkflow(
            steps = listOf(
                minimalStep(id = "step-1", dependsOn = listOf("missing-step"))
            ),
            environments = mapOf("env-1" to minimalEnvironment())
        )
        val result = WorkflowLinter.lint(workflow)
        val error = result.issues.find { it.message.contains("non-existent") }

        assertTrue(error != null)
        assertTrue(error.message.isNotBlank())
        assertTrue(error.message.contains("missing-step"))
    }

    // ── Check 9: Naming Convention Validation (Configurable) ──────────────────

    @Test
    fun `naming convention disabled by default in lenient config`()
    {
        val descriptor = workflowWithStepId("InvalidID")
        val result = WorkflowLinter.lint(descriptor, LinterConfiguration.LENIENT)

        val errors = result.issues.filter {
            it.code == ValidationErrorCode.NAMING_CONVENTION_VIOLATION
        }
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `naming convention detects invalid step id`()
    {
        val descriptor = workflowWithStepId("InvalidID")
        val result = WorkflowLinter.lint(descriptor, LinterConfiguration.STRICT)

        val error = result.issues.find {
            it.code == ValidationErrorCode.NAMING_CONVENTION_VIOLATION &&
                    it.path?.contains("steps") == true
        }

        assertNotNull(error)
        assertEquals(ValidationSeverity.WARNING, error.severity)
        assertTrue(error.message.contains("InvalidID"))
    }

    @Test
    fun `naming convention accepts valid step ids`()
    {
        val validIds = listOf("step-1", "step_2", "step3")
        validIds.forEach { validId ->
            val descriptor = workflowWithStepId(validId)
            val result = WorkflowLinter.lint(descriptor, LinterConfiguration.STRICT)

            val errors = result.issues.filter {
                it.code == ValidationErrorCode.NAMING_CONVENTION_VIOLATION
            }
            assertEquals(0, errors.size, "Valid ID '$validId' should not be flagged")
        }
    }

    @Test
    fun `naming convention detects invalid environment id`()
    {
        val descriptor = workflowWithEnvironmentId("InvalidEnv")
        val result = WorkflowLinter.lint(descriptor, LinterConfiguration.STRICT)

        val error = result.issues.find {
            it.code == ValidationErrorCode.NAMING_CONVENTION_VIOLATION &&
                    it.path?.contains("environments") == true
        }

        assertNotNull(error)
        assertEquals(ValidationSeverity.WARNING, error.severity)
    }

    @Test
    fun `naming convention accepts valid environment ids`()
    {
        val validIds = listOf("env-1", "env_2", "env3")
        validIds.forEach { validId ->
            val descriptor = workflowWithEnvironmentId(validId)
            val result = WorkflowLinter.lint(descriptor, LinterConfiguration.STRICT)

            val errors = result.issues.filter {
                it.code == ValidationErrorCode.NAMING_CONVENTION_VIOLATION &&
                        it.path?.contains("environments") == true
            }
            assertEquals(0, errors.size, "Valid environment ID '$validId' should not be flagged")
        }
    }

    // ── Check 10: Missing Metadata (Configurable) ─────────────────────────────

    @Test
    fun `missing metadata disabled by default`()
    {
        val descriptor = workflowWithStepMissingDescription()
        val result = WorkflowLinter.lint(descriptor, LinterConfiguration.DEFAULT)

        val errors = result.issues.filter {
            it.code == ValidationErrorCode.MISSING_METADATA
        }
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `missing metadata detects step without description`()
    {
        val descriptor = workflowWithStepMissingDescription()
        val result = WorkflowLinter.lint(descriptor, LinterConfiguration.STRICT)

        val error = result.issues.find {
            it.code == ValidationErrorCode.MISSING_METADATA
        }

        assertNotNull(error)
        assertEquals(ValidationSeverity.WARNING, error.severity)
        assertTrue(error.message.contains("description"))
    }

    @Test
    fun `missing metadata severity configurable`()
    {
        val descriptor = workflowWithStepMissingDescription()
        val config = LinterConfiguration(requireStepDescriptions = true, missingMetadataSeverity = "WARNING")
        val result = WorkflowLinter.lint(descriptor, config)

        val error = result.issues.find {
            it.code == ValidationErrorCode.MISSING_METADATA
        }

        assertNotNull(error)
        assertEquals(ValidationSeverity.WARNING, error.severity)
    }

    // ── Check 11 Unused Environments (Configurable) ──────────────────────────

    @Test
    fun `unused environment disabled in lenient config`()
    {
        val descriptor = workflowWithUnusedEnvironment()
        val result = WorkflowLinter.lint(descriptor, LinterConfiguration.LENIENT)

        val errors = result.issues.filter {
            it.code == ValidationErrorCode.UNUSED_ENVIRONMENT
        }
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `unused environment detected`()
    {
        val descriptor = workflowWithUnusedEnvironment()
        val result = WorkflowLinter.lint(descriptor, LinterConfiguration.STRICT)

        val error = result.issues.find {
            it.code == ValidationErrorCode.UNUSED_ENVIRONMENT
        }

        assertNotNull(error)
        assertEquals(ValidationSeverity.WARNING, error.severity)
        assertTrue(error.message.contains("unused"))
    }

    @Test
    fun `unused environment not reported when used`()
    {
        val descriptor = workflowWithAllEnvironmentsUsed()
        val result = WorkflowLinter.lint(descriptor, LinterConfiguration.STRICT)

        val errors = result.issues.filter {
            it.code == ValidationErrorCode.UNUSED_ENVIRONMENT
        }
        assertTrue(errors.isEmpty())
    }

    // ── Check 12: Workflow Length (Configurable) ──────────────────────────────

    @Test
    fun `workflow too long disabled with high threshold`()
    {
        val descriptor = workflowWithStepCount(15)
        val config = LinterConfiguration(maxStepsWarningThreshold = 100)
        val result = WorkflowLinter.lint(descriptor, config)

        val errors = result.issues.filter {
            it.code == ValidationErrorCode.WORKFLOW_TOO_LONG
        }
        assertTrue(errors.isEmpty(), "High threshold should not flag 15 steps")
    }

    @Test
    fun `workflow too long detected when exceeds threshold`()
    {
        val descriptor = workflowWithStepCount(15)
        val config = LinterConfiguration(maxStepsWarningThreshold = 10)
        val result = WorkflowLinter.lint(descriptor, config)

        val error = result.issues.find {
            it.code == ValidationErrorCode.WORKFLOW_TOO_LONG
        }

        assertNotNull(error)
        assertEquals(ValidationSeverity.WARNING, error.severity)
        assertTrue(error.message.contains("15"))
        assertTrue(error.message.contains("10"))
    }

    @Test
    fun `workflow length threshold configurable`()
    {
        val descriptor = workflowWithStepCount(5)
        val config = LinterConfiguration(maxStepsWarningThreshold = 3)
        val result = WorkflowLinter.lint(descriptor, config)

        val error = result.issues.find {
            it.code == ValidationErrorCode.WORKFLOW_TOO_LONG
        }

        assertNotNull(error, "Should flag when exceeding custom threshold of 3")
        assertTrue(error.message.contains("5"))
    }

    @Test
    fun `workflow length not flagged at threshold`()
    {
        val descriptor = workflowWithStepCount(10)
        val config = LinterConfiguration(maxStepsWarningThreshold = 10)
        val result = WorkflowLinter.lint(descriptor, config)

        val errors = result.issues.filter {
            it.code == ValidationErrorCode.WORKFLOW_TOO_LONG
        }
        assertTrue(errors.isEmpty(), "Should not flag when at threshold (not exceeding)")
    }

    // ── Configuration Integration Tests ────────────────────────────────────────

    @Test
    fun `lenient config disables all extended rules`()
    {
        val descriptor = workflowWithAllViolations()
        val result = WorkflowLinter.lint(descriptor, LinterConfiguration.LENIENT)

        val extendedRuleErrors = result.issues.filter { issue ->
            issue.code in listOf(
                ValidationErrorCode.NAMING_CONVENTION_VIOLATION,
                ValidationErrorCode.DEPRECATED_VOCABULARY_DETECTED,
                ValidationErrorCode.MISSING_METADATA,
                ValidationErrorCode.UNUSED_ENVIRONMENT,
                ValidationErrorCode.WORKFLOW_TOO_LONG
            )
        }

        assertTrue(extendedRuleErrors.isEmpty())
    }

    @Test
    fun `strict config enables all extended rules`()
    {
        val descriptor = workflowWithAllViolations()
        val result = WorkflowLinter.lint(descriptor, LinterConfiguration.STRICT)

        val extendedRuleErrors = result.issues.filter { issue ->
            issue.code in listOf(
                ValidationErrorCode.NAMING_CONVENTION_VIOLATION,
                ValidationErrorCode.DEPRECATED_VOCABULARY_DETECTED,
                ValidationErrorCode.MISSING_METADATA,
                ValidationErrorCode.UNUSED_ENVIRONMENT,
                ValidationErrorCode.WORKFLOW_TOO_LONG
            )
        }

        assertTrue(extendedRuleErrors.isNotEmpty())
    }

    // ── Helper Methods for Test Fixtures ───────────────────────────────────────

    private fun workflowWithStepId(stepId: String): WorkflowDescriptor =
        minimalWorkflow().copy(
            steps = listOf(
                StepDescriptor(
                    id = stepId,
                    environmentId = "env-1",
                    task = CommandTaskDescriptor(name = "test", executable = "echo", args = emptyList())
                )
            )
        )

    private fun workflowWithEnvironmentId(envId: String): WorkflowDescriptor =
        minimalWorkflow().copy(
            environments = mapOf(
                envId to EnvironmentDescriptor(name = "test", kind = "system")
            ),
            steps = listOf(
                StepDescriptor(
                    id = "step-1",
                    environmentId = envId,
                    task = CommandTaskDescriptor(name = "test", executable = "echo", args = emptyList())
                )
            )
        )

    private fun workflowWithStepMissingDescription(): WorkflowDescriptor =
        minimalWorkflow().copy(
            steps = listOf(
                StepDescriptor(
                    id = "step-1",
                    environmentId = "env-1",
                    metadata = StepMetadataDescriptor(name = "step-1", description = null),
                    task = CommandTaskDescriptor(name = "test", executable = "echo", args = emptyList())
                )
            )
        )

    private fun workflowWithUnusedEnvironment(): WorkflowDescriptor =
        minimalWorkflow().copy(
            environments = mapOf(
                "env-used" to EnvironmentDescriptor(name = "used", kind = "system"),
                "env-unused" to EnvironmentDescriptor(name = "unused", kind = "system")
            ),
            steps = listOf(
                StepDescriptor(
                    id = "step-1",
                    environmentId = "env-used",
                    task = CommandTaskDescriptor(name = "test", executable = "echo", args = emptyList())
                )
            )
        )

    private fun workflowWithAllEnvironmentsUsed(): WorkflowDescriptor =
        minimalWorkflow().copy(
            environments = mapOf(
                "env-1" to EnvironmentDescriptor(name = "env1", kind = "system"),
                "env-2" to EnvironmentDescriptor(name = "env2", kind = "system")
            ),
            steps = listOf(
                StepDescriptor(
                    id = "step-1",
                    environmentId = "env-1",
                    task = CommandTaskDescriptor(name = "test", executable = "echo", args = emptyList())
                ),
                StepDescriptor(
                    id = "step-2",
                    environmentId = "env-2",
                    task = CommandTaskDescriptor(name = "test2", executable = "echo", args = emptyList())
                )
            )
        )

    private fun workflowWithStepCount(count: Int): WorkflowDescriptor =
        minimalWorkflow().copy(
            steps = (1..count).map { i ->
                StepDescriptor(
                    id = "step-$i",
                    environmentId = "env-1",
                    task = CommandTaskDescriptor(name = "task-$i", executable = "echo", args = emptyList())
                )
            }
        )

    private fun workflowWithAllViolations(): WorkflowDescriptor =
        WorkflowDescriptor(
            schemaVersion = "1.0",
            metadata = WorkflowMetadataDescriptor(
                name = "test",
                description = "This is a Process" // Deprecated vocabulary
            ),
            environments = mapOf(
                "InvalidEnv" to EnvironmentDescriptor(name = "test", kind = "system"), // Naming violation
                "env-unused" to EnvironmentDescriptor(name = "unused", kind = "system") // Unused
            ),
            steps = (1..15).map { i -> // Too long
                StepDescriptor(
                    id = "InvalidStep$i", // Naming violation
                    environmentId = if (i == 1) "InvalidEnv" else "env-unused",
                    metadata = StepMetadataDescriptor(
                        name = "step-$i",
                        description = null // Missing metadata
                    ),
                    task = CommandTaskDescriptor(
                        name = "task-$i",
                        executable = "echo",
                        args = emptyList()
                    )
                )
            }
        )

    private fun minimalWorkflow(): WorkflowDescriptor =
        WorkflowDescriptor(
            schemaVersion = "1.0",
            metadata = WorkflowMetadataDescriptor(name = "test"),
            environments = mapOf(
                "env-1" to EnvironmentDescriptor(name = "system", kind = "system")
            ),
            steps = emptyList()
        )
}
