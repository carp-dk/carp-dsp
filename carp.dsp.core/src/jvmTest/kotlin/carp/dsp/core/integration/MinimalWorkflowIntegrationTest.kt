package carp.dsp.core.integration

import carp.dsp.core.application.authoring.mapper.WorkflowDescriptorImporter
import dk.cachet.carp.analytics.application.execution.ExecutionIssueKind
import dk.cachet.carp.analytics.application.execution.ExecutionStatus
import dk.cachet.carp.analytics.application.execution.workspace.WorkspacePathFormatter
import dk.cachet.carp.analytics.application.plan.SystemEnvironmentRef
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration test for the minimal workflow.
 *
 * Fixture: minimal-valid.yaml
 * Environment: System (no Conda/Pixi/R needed)
 */
class MinimalWorkflowIntegrationTest : IntegrationTestBase()
{
    /**
     * Test 1: Happy path with system environment.
     *
     * Validates:
     * - Fixture loads correctly
     * - Plan generation works
     * - Execution completes without errors
     * - No issues recorded
     *
     * This is the most basic test - just load, plan, execute, verify success.
     */
    @Test
    fun `happy path with system environment`()
    {
        // 1. Load fixture
        val descriptor = loadFixture( "minimal-valid.yaml" )
        assertNotNull( descriptor )

        // 2. Generate plan
        val definition = WorkflowDescriptorImporter().import( descriptor )
        val plan = generateExecutionPlan( definition )

        // 3. Execute
        val report = executeAndVerify( plan )

        // 4. Assert success
        assertExecutionSucceeded( report )

        // 5. Verify no issues recorded
        assertTrue(
            report.issues.isEmpty(),
            "System environment should not generate any issues"
        )
    }

    /**
     * Test 2: Step execution order is deterministic.
     *
     * Validates:
     * - Steps are ordered correctly in plan
     * - Step order matches fixture (echo-hello before echo-goodbye)
     * - Dependency resolution works
     * - Execution order matches plan order
     *
     * This tests that topological sorting is correct.
     */
    @Test
    fun `step execution order is deterministic`()
    {
        // 1. Load fixture
        val descriptor = loadFixture( "minimal-valid.yaml" )

        // 2. Generate plan
        val definition = WorkflowDescriptorImporter().import( descriptor )
        val plan = generateExecutionPlan( definition )

        // 3. Verify plan has 2 steps
        assertPlanHasSteps( plan, expectedCount = 1 )

        // 4. Verify plan order
        val planStepIds = plan.steps.map { it.metadata.id }

        // 5. Execute
        val report = executeAndVerify( plan )

        // 6. Verify execution order matches plan
        val reportStepNames = report.stepResults.map { it.stepMetadata.id }
        assertEquals(
            planStepIds.toString(),
            reportStepNames.toString(),
            "Execution order should match plan order"
        )

        // 7. Verify both steps succeeded
        report.stepResults.forEach { stepResult ->
            assertEquals(
                ExecutionStatus.SUCCEEDED,
                stepResult.status,
                "Step ${stepResult.stepMetadata.name} should succeed"
            )
        }
    }

    /**
     * Test 3: Execution report contains all required fields.
     *
     * Validates:
     * - Report has planId
     * - Report has runId
     * - Report has correct status
     * - Report has timing (startedAt, finishedAt)
     * - Report has all step results
     * - Timing is correct (finishedAt >= startedAt)
     *
     * This tests that the execution report structure is complete and correct.
     */
    @Test
    fun `execution report contains all required fields`()
    {
        val descriptor = loadFixture( "minimal-valid.yaml" )
        val definition = WorkflowDescriptorImporter().import( descriptor )
        val plan = generateExecutionPlan( definition )
        val report = executeAndVerify( plan )

        // Verify report top-level fields
        assertNotNull( report.planId, "planId should be set" )
        assertNotNull( report.runId, "runId should be set" )
        assertEquals( ExecutionStatus.SUCCEEDED, report.status, "status should be SUCCEEDED" )
        assertNotNull( report.startedAt, "startedAt should be set" )
        assertNotNull( report.finishedAt, "finishedAt should be set" )

        // Verify timing
        assertTrue(
            report.finishedAt!! >= report.startedAt!!,
            "finishedAt should be >= startedAt"
        )

        // Verify steps
        assertEquals(
            1,
            report.stepResults.size,
            "Report should have 2 step results"
        )

        // Verify each step result has required fields
        report.stepResults.forEach { stepResult ->
            assertNotNull( stepResult.stepMetadata.id, "stepMetadata should be set" )
            assertEquals(
                ExecutionStatus.SUCCEEDED,
                stepResult.status,
                "Step ${stepResult.stepMetadata.id} status should be SUCCEEDED"
            )
            assertNotNull( stepResult.startedAt, "Step ${stepResult.stepMetadata.name} startedAt should be set" )
            assertNotNull( stepResult.finishedAt, "Step ${stepResult.stepMetadata.name} finishedAt should be set" )

            // Verify step timing is correct
            assertTrue(
                stepResult.startedAt!! >= report.startedAt!!,
                "Step startedAt should be >= workflow startedAt"
            )
            assertTrue(
                stepResult.finishedAt!! >= stepResult.startedAt!!,
                "Step finishedAt should be >= step startedAt"
            )
            assertTrue(
                stepResult.finishedAt!! <= report.finishedAt!!,
                "Step finishedAt should be <= workflow finishedAt"
            )
        }
    }

    /**
     * Test 4: System environment requires no provisioning.
     *
     * Validates:
     * - Plan references system environment
     * - Execution completes without environment setup
     * - No orchestrator errors recorded
     * - No environment setup issues
     *
     * This tests that system environment doesn't require provisioning.
     */
    @Test
    fun `system environment requires no provisioning`()
    {
        val descriptor = loadFixture( "minimal-valid.yaml" )
        val definition = WorkflowDescriptorImporter().import( descriptor )
        val plan = generateExecutionPlan( definition )

        // Verify plan has system environment reference
        val systemEnvRefs = plan.requiredEnvironmentRefs.values
            .filterIsInstance<SystemEnvironmentRef>()

        assertTrue(
            systemEnvRefs.isNotEmpty(),
            "Plan should reference system environment"
        )

        // Execute (should not require environment setup)
        val report = executeAndVerify( plan )

        // Verify execution succeeded
        assertExecutionSucceeded( report )

        // Verify no environment setup issues
        val envSetupIssues = report.issues.filter {
            it.kind == ExecutionIssueKind.ORCHESTRATOR_ERROR
        }
        assertTrue(
            envSetupIssues.isEmpty(),
            "System environment should not cause orchestrator errors, but got: ${envSetupIssues.map { it.message }}"
        )

        // Verify no other execution errors
        val processErrors = report.issues.filter {
            it.kind == ExecutionIssueKind.PROCESS_FAILED
        }
        assertTrue(
            processErrors.isEmpty(),
            "Echo commands should not fail, but got: ${processErrors.map { it.message }}"
        )
    }

    @Test
    fun `output file is written to workspace-relative path`()
    {
        val descriptor = loadFixture( "minimal-valid.yaml" )
        val definition = WorkflowDescriptorImporter().import( descriptor )
        val plan = generateExecutionPlan( definition )

        val report = executeAndVerify( plan )
        assertExecutionSucceeded( report )


        // Output file is at: {tmpDir}/{normalizedWorkflowName}/run_{runId}/steps/01_write_file/outputs/output-txt.txt
        val normalizedWorkflowName = WorkspacePathFormatter.formatWorkflowName( plan.workflowName )
        val outputFile = tmpDir.resolve( normalizedWorkflowName )
            .resolve( "run_${report.runId}" )
            .resolve( "steps/01_write_file/outputs/output-txt.txt" )
        assertTrue( outputFile.exists(), "Output should exist at workspace-relative path: $outputFile" )
        assertTrue( outputFile.readText().contains( "hello" ) )
    }
}
