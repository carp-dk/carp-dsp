package carp.dsp.core.integration

import carp.dsp.core.application.authoring.mapper.WorkflowDescriptorImporter
import dk.cachet.carp.analytics.application.execution.ExecutionStatus
import dk.cachet.carp.analytics.application.plan.CondaEnvironmentRef
import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Single-step Python workflow with Conda environment.
 * Fixture: single-step-python-conda.yaml
 *
 * Runs the workflow; validates plan structure, execution, and report.
 */
class SingleStepPythonCondaTest : IntegrationTestBase()
{
    private lateinit var plan: ExecutionPlan

    @BeforeTest
    fun setupTestData()
    {
        val descriptor = loadFixture( "single-step-python-conda.yaml" )
        val definition = WorkflowDescriptorImporter().import( descriptor )
        plan = generateExecutionPlan( definition )

        val root = executionRootFor( plan )
        setupWorkspaceAssets(
            root,
            "scripts/process_data.py" to "scripts/process_data.py",
            "data/input.csv" to "data/input.csv"
        )
    }

    @Test
    fun `conda workflow executes successfully`()
    {
        // Plan structure
        assertPlanValid( plan )
        assertPlanHasSteps( plan, expectedCount = 1 )

        val condaRefs = plan.requiredEnvironmentRefs.values.filterIsInstance<CondaEnvironmentRef>()
        assertTrue( condaRefs.isNotEmpty(), "Plan should have a Conda environment reference" )
        val condaRef = condaRefs.first()
        assertEquals( "python-processing", condaRef.name )
        assertTrue( condaRef.dependencies.contains( "pandas" ), "Should depend on pandas" )

        // Execute
        val report = executeAndVerify( plan )

        // Report completeness
        assertNotNull( report.planId )
        assertNotNull( report.runId )
        assertNotNull( report.startedAt )
        assertNotNull( report.finishedAt )
        assertTrue( report.finishedAt!! >= report.startedAt!! )

        // Execution result
        assertExecutionSucceeded( report )
        assertEquals( ExecutionStatus.SUCCEEDED, report.status )

        assertEquals( 1, report.stepResults.size )
        val stepResult = report.stepResults.first()
        assertEquals( ExecutionStatus.SUCCEEDED, stepResult.status )
        assertTrue( stepResult.finishedAt!! >= stepResult.startedAt!! )

        // Output file written
        val outputFile = executionRootFor( plan )
            .resolve( "steps/01_process_data_with_python/outputs/output-csv.csv" )
        assertTrue( outputFile.exists(), "Output file should exist at $outputFile" )
        val content = outputFile.readText()
        assertTrue( content.contains( "col1" ), "Output should preserve input columns" )
        assertTrue( content.contains( "processed_value" ), "Output should have processed_value column" )
    }
}
