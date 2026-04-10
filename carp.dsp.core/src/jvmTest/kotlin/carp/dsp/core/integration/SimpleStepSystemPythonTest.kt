package carp.dsp.core.integration

import carp.dsp.core.application.authoring.mapper.WorkflowDescriptorImporter
import dk.cachet.carp.analytics.application.execution.ExecutionStatus
import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import kotlin.io.path.*
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Fast integration test using system Python (no Conda setup).
 *
 * Fixture: simple-step-system-python.yaml
 * Environment: System (no setup/teardown overhead)
 * Task: Python script execution
 * Duration: < 1 second
 *
 * This test is much faster than Conda-based tests because:
 * - No environment provisioning
 * - No environment teardown
 * - Uses default system Python
 * - Minimal Python dependencies (just csv and datetime - both stdlib)
 */
class SimpleStepSystemPythonTest : IntegrationTestBase()
{
    private lateinit var plan: ExecutionPlan

    @BeforeTest
    fun setupTestData()
    {
        // Generate plan so we can derive executionRoot
        val descriptor = loadFixture( "simple-step-system-python.yaml" )
        val definition = WorkflowDescriptorImporter().import( descriptor )
        plan = generateExecutionPlan( definition )

        val root = executionRootFor( plan )

        // Copy script and input data into executionRoot so the process can find them
        setupWorkspaceAssets(
            root,
            "scripts/simple_process.py" to "scripts/simple_process.py",
            "data/input.csv" to "inputs/input.csv"
        )
    }

    @Test
    fun `system python executes successfully`()
    {
        assertPlanValid( plan )
        assertPlanHasSteps( plan, expectedCount = 1 )

        val sysRefs = plan.requiredEnvironmentRefs.values
        assertTrue( sysRefs.isNotEmpty(), "Plan should have environment references" )

        val report = executeAndVerify( plan )

        // Assert execution succeeded
        assertExecutionSucceeded( report )

        // Assert output file was created at the workspace-relative path
        val outputFile = executionRootFor( plan )
            .resolve( "steps/01_process_data/outputs/output-csv.csv" )
        assertTrue( outputFile.exists(), "Output file should be created at $outputFile" )

        val outputContent = outputFile.readText()
        assertTrue( outputContent.contains( "col1,col2,col3" ), "Output should have original columns" )
        assertTrue( outputContent.contains( "processed_at" ), "Output should have processed_at column" )
        assertTrue( outputContent.contains( "value1,value2,value3" ), "Output should have data rows" )
    }

    @Test
    fun `plan has correct step ordering`()
    {
        assertPlanHasSteps( plan, expectedCount = 1 )
        assertEquals( "Process Data", plan.steps.first().metadata.name )
    }

    @Test
    fun `execution report is complete`()
    {
        val report = executeAndVerify( plan )

        assertNotNull( report.planId )
        assertNotNull( report.runId )
        assertNotNull( report.status )
        assertNotNull( report.issues )
        assertNotNull( report.startedAt )
        assertNotNull( report.finishedAt )
        assertNotNull( report.stepResults )

        assertTrue( report.finishedAt!! >= report.startedAt!!, "Finish time >= start time" )

        assertEquals( 1, report.stepResults.size )
        val stepResult = report.stepResults.first()
        assertNotNull( stepResult.stepMetadata.id )
        assertNotNull( stepResult.stepMetadata.name )
        assertEquals( ExecutionStatus.SUCCEEDED, stepResult.status )
        assertTrue( stepResult.finishedAt!! >= stepResult.startedAt!! )
    }
}
