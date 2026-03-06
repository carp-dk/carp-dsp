package carp.dsp.core.application.authoring.validation

import carp.dsp.core.application.authoring.descriptor.CommandTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.DataPortDescriptor
import carp.dsp.core.application.authoring.descriptor.EnvironmentDescriptor
import carp.dsp.core.application.authoring.descriptor.StepDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowMetadataDescriptor
import dk.cachet.carp.common.application.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
    fun `lint empty workflow returns no issues`()
    {
        val workflow = minimalWorkflow()
        val issues = WorkflowLinter.lint(workflow)
        assertEquals(0, issues.size)
    }

    @Test
    fun `lint valid single-step workflow returns no issues`()
    {
        val workflow = minimalWorkflow(
            steps = listOf(minimalStep()),
            environments = mapOf("env-1" to minimalEnvironment())
        )
        val issues = WorkflowLinter.lint(workflow)
        assertEquals(0, issues.size)
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
        val issues = WorkflowLinter.lint(workflow)
        assertEquals(0, issues.size)
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
        val issues = WorkflowLinter.lint(workflow)
        val errors = issues.filter { it.level == "ERROR" && it.message.contains("duplicated") }
        assertEquals(1, errors.size)
        assertTrue(errors[0].message.contains("step-1"))
        assertTrue(errors[0].message.contains("2 times"))
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
        val issues = WorkflowLinter.lint(workflow)
        val errors = issues.filter { it.level == "ERROR" && it.message.contains("duplicated") }
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
        val issues = WorkflowLinter.lint(workflow)
        val errors = issues.filter { it.level == "ERROR" && it.fieldName == "inputs" }
        assertEquals(1, errors.size)
        assertTrue(errors[0].message.contains("port-1"))
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
        val issues = WorkflowLinter.lint(workflow)
        val errors = issues.filter { it.level == "ERROR" && it.fieldName == "outputs" }
        assertEquals(1, errors.size)
        assertTrue(errors[0].message.contains("out-1"))
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
        val issues = WorkflowLinter.lint(workflow)
        val errors = issues.filter { it.level == "ERROR" && it.message.contains("not found") }
        assertEquals(1, errors.size)
        assertTrue(errors[0].message.contains("missing-env"))
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
        val issues = WorkflowLinter.lint(workflow)
        val warnings = issues.filter { it.level == "WARNING" && it.message.contains("not recognized") }
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].message.contains("docker"))
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
            val issues = WorkflowLinter.lint(workflow)
            val kindWarnings = issues.filter { it.level == "WARNING" && it.message.contains("not recognized") }
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
        val issues = WorkflowLinter.lint(workflow)
        val kindWarnings = issues.filter { it.level == "WARNING" && it.message.contains("not recognized") }
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
        val issues = WorkflowLinter.lint(workflow)
        val warnings = issues.filter { it.level == "WARNING" && it.fieldName == "id" }
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].message.contains("not a valid UUID"))
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
        val issues = WorkflowLinter.lint(workflow)
        val warnings = issues.filter { it.level == "WARNING" && it.fieldName == "task.id" }
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].message.contains("not a valid UUID"))
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
        val issues = WorkflowLinter.lint(workflow)
        val uuidWarnings = issues.filter { it.level == "WARNING" && it.message.contains("not a valid UUID") }
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
        val issues = WorkflowLinter.lint(workflow)
        val errors = issues.filter { it.level == "ERROR" && it.message.contains("non-existent") }
        assertEquals(1, errors.size)
        assertTrue(errors[0].message.contains("missing-step"))
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
        val issues = WorkflowLinter.lint(workflow)
        val errors = issues.filter { it.level == "ERROR" && it.message.contains("non-existent") }
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
        val issues = WorkflowLinter.lint(workflow)
        val errors = issues.filter { it.level == "ERROR" && it.message.contains("Circular") }
        assertEquals(1, errors.size)
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
        val issues = WorkflowLinter.lint(workflow)
        val errors = issues.filter { it.level == "ERROR" && it.message.contains("Circular") }
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
        val issues = WorkflowLinter.lint(workflow)
        val errors = issues.filter { it.level == "ERROR" && it.message.contains("Circular") }
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
        val issues = WorkflowLinter.lint(workflow)
        val cycles = issues.filter { it.level == "ERROR" && it.message.contains("Circular") }
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
        val issues = WorkflowLinter.lint(workflow)
        val errors = issues.filter { it.level == "ERROR" }
        // Should catch: duplicate step ID + missing upstream reference
        assertTrue(errors.size >= 2, "Should catch multiple errors")
    }

    // ── Issue Properties ──────────────────────────────────────────────────────

    @Test
    fun `lint issue has correct location information`()
    {
        val workflow = minimalWorkflow(
            steps = listOf(
                minimalStep(id = "step-1", environmentId = "missing-env")
            ),
            environments = emptyMap()
        )
        val issues = WorkflowLinter.lint(workflow)
        val error = issues.find { it.message.contains("not found") }

        assertEquals("step-1", error?.stepId)
        assertEquals("environmentId", error?.fieldName)
    }

    @Test
    fun `lint issue has suggestion`()
    {
        val workflow = minimalWorkflow(
            steps = listOf(
                minimalStep(id = "step-1", dependsOn = listOf("missing-step"))
            ),
            environments = mapOf("env-1" to minimalEnvironment())
        )
        val issues = WorkflowLinter.lint(workflow)
        val error = issues.find { it.message.contains("non-existent") }

        assertTrue(error?.suggestion != null)
        assertTrue(error.suggestion.isNotBlank())
    }

    // ── LintIssue Companion Methods ───────────────────────────────────────────

    @Test
    fun `LintIssue_error creates error issue`()
    {
        val issue = LintIssue.error("Test error", stepId = "step-1")
        assertEquals("ERROR", issue.level)
        assertEquals("Test error", issue.message)
        assertEquals("step-1", issue.stepId)
    }

    @Test
    fun `LintIssue_warning creates warning issue`()
    {
        val issue = LintIssue.warning("Test warning")
        assertEquals("WARNING", issue.level)
        assertEquals("Test warning", issue.message)
    }

    @Test
    fun `LintIssue validates level is ERROR or WARNING`()
    {
        val ex = assertFailsWith<IllegalArgumentException> {
            LintIssue("INVALID", "message")
        }
        assertTrue( ex.message!!.contains("'ERROR' or 'WARNING'") )
    }
}
