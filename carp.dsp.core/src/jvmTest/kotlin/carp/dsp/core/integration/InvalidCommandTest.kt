package carp.dsp.core.integration

import carp.dsp.core.application.authoring.mapper.WorkflowDescriptorImporter
import dk.cachet.carp.analytics.application.execution.ExecutionIssueKind
import dk.cachet.carp.analytics.application.execution.ExecutionStatus
import dk.cachet.carp.analytics.application.execution.FailureKind
import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies failure handling when the command script file does not exist.
 *
 * Setup: the input CSV is present, but the Python script is NOT copied into the workspace.
 * Python exits with a non-zero code ("can't open file: No such file or directory"),
 * which must produce a FAILED report with a COMMAND_FAILED step failure and an
 * OUTPUT_MISSING issue (the output was never produced).
 *
 * Fixture: simple-step-system-python.yaml
 */
class InvalidCommandTest : IntegrationTestBase()
{
    private lateinit var plan: ExecutionPlan

    @BeforeTest
    fun setupTestData()
    {
        val descriptor = loadFixture( "simple-step-system-python.yaml" )
        val definition = WorkflowDescriptorImporter().import( descriptor )
        plan = generateExecutionPlan( definition )

        val root = executionRootFor( plan )

        // Copy the input CSV, but intentionally omit the Python script.
        // Python will be invoked but the script path does not exist → exit code 2.
        setupWorkspaceAssets(
            root,
            "data/input.csv" to "inputs/input.csv"
            // "scripts/simple_process.py" is NOT copied
        )
    }

    @Test
    fun `missing script exits non-zero and produces FAILED report with structured issue`()
    {
        val report = executeAndVerify( plan )

        // Overall status must be FAILED
        assertEquals(
            ExecutionStatus.FAILED,
            report.status,
            "Report status should be FAILED when the script file is missing"
        )

        // The step itself must be FAILED
        val stepResult = report.stepResults.single()
        assertEquals( ExecutionStatus.FAILED, stepResult.status, "Step status should be FAILED" )

        // The process exited with a non-zero code → failure kind COMMAND_FAILED
        assertNotNull( stepResult.failure, "Step should carry a failure detail" )
        assertEquals(
            FailureKind.COMMAND_FAILED,
            stepResult.failure!!.kind,
            "Step failure kind should be COMMAND_FAILED"
        )
    }
}
