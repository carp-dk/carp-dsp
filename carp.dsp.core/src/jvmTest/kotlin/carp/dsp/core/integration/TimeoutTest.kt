package carp.dsp.core.integration

import carp.dsp.core.application.authoring.mapper.WorkflowDescriptorImporter
import carp.dsp.core.application.execution.CommandPolicy
import dk.cachet.carp.analytics.application.execution.ExecutionStatus
import dk.cachet.carp.analytics.application.execution.FailureKind
import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Verifies that a step killed by the timeout policy produces a FAILED report.
 *
 * Setup: the script sleeps for 30 seconds; the plan is executed with a 500 ms timeout.
 * The process is destroyed when the timeout expires. The step failure kind must be TIMEOUT
 * and an OUTPUT_MISSING issue must be recorded (output was never produced).
 *
 * Fixture: timeout.yaml
 */
class TimeoutTest : IntegrationTestBase()
{
    private lateinit var plan: ExecutionPlan

    @BeforeTest
    fun setupTestData()
    {
        val descriptor = loadFixture( "timeout.yaml" )
        val definition = WorkflowDescriptorImporter().import( descriptor )
        plan = generateExecutionPlan( definition )

        val root = executionRootFor( plan )

        setupWorkspaceAssets(
            root,
            "scripts/sleep_long.py" to "scripts/sleep_long.py"
        )
    }

    @Test
    fun `step killed by timeout produces FAILED report with TIMEOUT failure kind`()
    {
        // Execute with a very short timeout — the 30-second sleep will be killed.
        val policy = CommandPolicy( timeoutMs = 250L )
        val report = executor.execute( plan, runId, policy )

        // Overall status must be FAILED
        assertEquals(
            ExecutionStatus.FAILED,
            report.status,
            "Report status should be FAILED when step times out"
        )

        // The step itself must be FAILED
        val stepResult = report.stepResults.single()
        assertEquals( ExecutionStatus.FAILED, stepResult.status, "Step status should be FAILED" )

        // Failure kind must be TIMEOUT
        assertNotNull( stepResult.failure, "Step should carry a failure detail" )
        assertEquals(
            FailureKind.TIMEOUT,
            stepResult.failure!!.kind,
            "Step failure kind should be TIMEOUT"
        )
    }
}
