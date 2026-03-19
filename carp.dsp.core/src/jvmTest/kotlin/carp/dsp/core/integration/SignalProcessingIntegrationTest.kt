package carp.dsp.core.integration

import carp.dsp.core.application.authoring.mapper.WorkflowDescriptorImporter
import dk.cachet.carp.analytics.application.execution.ExecutionStatus
import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import dk.cachet.carp.analytics.application.plan.SystemEnvironmentRef
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Four-step signal processing pipeline using system Python.
 * Fixture: signal-processing.yaml
 *
 * Steps:
 *   1. Validate Signal   — check columns, add valid=true
 *   2. Preprocess Signal — z-score normalize channels
 *   3. Extract Features  — compute mean/std/min/max per channel
 *   4. Generate Report   — summarize features with a summary column
 */
class SignalProcessingIntegrationTest : IntegrationTestBase()
{
    private lateinit var plan: ExecutionPlan

    @BeforeTest
    fun setupTestData()
    {
        val descriptor = loadFixture( "signal-processing.yaml" )
        val definition = WorkflowDescriptorImporter().import( descriptor )
        plan = generateExecutionPlan( definition )

        val root = executionRootFor( plan )
        setupWorkspaceAssets(
            root,
            "scripts/sp_validate.py" to "scripts/sp_validate.py",
            "scripts/sp_preprocess.py" to "scripts/sp_preprocess.py",
            "scripts/sp_extract_features.py" to "scripts/sp_extract_features.py",
            "scripts/sp_generate_report.py" to "scripts/sp_generate_report.py",
            "data/eeg_signal.csv" to "inputs/eeg_signal.csv"
        )
    }

    @Test
    fun `signal processing pipeline executes successfully`()
    {
        // Plan structure
        assertPlanValid( plan )
        assertPlanHasSteps( plan, expectedCount = 4 )
        assertStepsOrdered(
            plan,
            listOf( "Validate Signal", "Preprocess Signal", "Extract Features", "Generate Report" )
        )

        // All steps use system environment
        val envRefs = plan.requiredEnvironmentRefs.values
        assertTrue( envRefs.all { it is SystemEnvironmentRef }, "All steps should use system environment" )

        // Execute
        val report = executeAndVerify( plan )

        // Report completeness
        assertNotNull( report.planId )
        assertNotNull( report.runId )
        assertNotNull( report.startedAt )
        assertNotNull( report.finishedAt )
        assertTrue( report.finishedAt!! >= report.startedAt!! )

        // All steps succeeded
        assertExecutionSucceeded( report )
        assertEquals( 4, report.stepResults.size )
        report.stepResults.forEach { step ->
            assertEquals( ExecutionStatus.SUCCEEDED, step.status )
            assertTrue( step.finishedAt!! >= step.startedAt!! )
        }

        val root = executionRootFor( plan )

        // Step 1 (validate): valid column added
        val validatedFile = root.resolve( "steps/01_validate_signal/outputs/validated-csv.csv" )
        assertTrue( validatedFile.exists(), "Step 1 output should exist at $validatedFile" )
        val validatedContent = validatedFile.readText()
        assertTrue( validatedContent.contains( "valid" ), "Step 1 output should have valid column" )
        assertTrue( validatedContent.contains( "true" ), "Step 1 output should have valid=true" )

        // Step 2 (preprocess): channel columns still present (values are normalised floats)
        val preprocessedFile = root.resolve( "steps/02_preprocess_signal/outputs/preprocessed-csv.csv" )
        assertTrue( preprocessedFile.exists(), "Step 2 output should exist at $preprocessedFile" )
        val preprocessedContent = preprocessedFile.readText()
        assertTrue( preprocessedContent.contains( "channel_1" ), "Step 2 output should have channel_1" )
        assertTrue( preprocessedContent.contains( "channel_2" ), "Step 2 output should have channel_2" )
        assertTrue( preprocessedContent.contains( "channel_3" ), "Step 2 output should have channel_3" )

        // Step 3 (extract features): one row per channel with stats
        val featuresFile = root.resolve( "steps/03_extract_features/outputs/features-csv.csv" )
        assertTrue( featuresFile.exists(), "Step 3 output should exist at $featuresFile" )
        val featuresContent = featuresFile.readText()
        assertTrue( featuresContent.contains( "channel_1" ), "Step 3 output should have channel_1 row" )
        assertTrue( featuresContent.contains( "mean" ), "Step 3 output should have mean column" )
        assertTrue( featuresContent.contains( "std" ), "Step 3 output should have std column" )

        // Step 4 (report): summary column present
        val reportFile = root.resolve( "steps/04_generate_report/outputs/report-csv.csv" )
        assertTrue( reportFile.exists(), "Step 4 output should exist at $reportFile" )
        val reportContent = reportFile.readText()
        assertTrue( reportContent.contains( "summary" ), "Step 4 output should have summary column" )
        assertTrue( reportContent.contains( "mean=" ), "Step 4 summary should contain mean= label" )
    }
}
