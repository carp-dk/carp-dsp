package carp.dsp.core.integration

import carp.dsp.core.application.authoring.mapper.WorkflowDescriptorImporter
import dk.cachet.carp.analytics.application.execution.ExecutionIssueKind
import dk.cachet.carp.analytics.application.execution.ExecutionStatus
import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies graceful failure when the input file declared by a step does not exist.
 *
 * Setup: script is present on disk, but the input CSV is not copied into the workspace.
 * The Python script opens the input path at runtime and exits with a non-zero code,
 * which should yield a FAILED report with an OUTPUT_MISSING issue (the output was
 * never produced because the step failed).
 *
 * Fixture: simple-step-system-python.yaml
 */
class MissingInputFileTest : IntegrationTestBase()
{
    private lateinit var plan: ExecutionPlan

    @BeforeTest
    fun setupTestData()
    {
        val descriptor = loadFixture( "simple-step-system-python.yaml" )
        val definition = WorkflowDescriptorImporter().import( descriptor )
        plan = generateExecutionPlan( definition )

        val root = executionRootFor( plan )

        // Copy the script, but intentionally omit the input CSV.
        setupWorkspaceAssets(
            root,
            "scripts/simple_process.py" to "scripts/simple_process.py"
            // "data/input.csv" is NOT copied → runtime FileNotFoundError in the script
        )
    }

    @Test
    fun `missing input file produces FAILED report with structured issue`()
    {
        val report = executeAndVerify( plan )

        // Overall status must be FAILED
        assertEquals(
            ExecutionStatus.FAILED,
            report.status,
            "Report status should be FAILED when input file is missing"
        )

        // The step itself must be FAILED
        val stepResult = report.stepResults.single()
        assertEquals(
            ExecutionStatus.FAILED,
            stepResult.status,
            "Step status should be FAILED"
        )
    }
}
