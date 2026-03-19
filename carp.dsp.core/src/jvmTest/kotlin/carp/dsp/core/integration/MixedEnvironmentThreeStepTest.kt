package carp.dsp.core.integration

import carp.dsp.core.application.authoring.mapper.WorkflowDescriptorImporter
import dk.cachet.carp.analytics.application.execution.ExecutionStatus
import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import dk.cachet.carp.analytics.application.plan.PixiEnvironmentRef
import dk.cachet.carp.analytics.application.plan.REnvironmentRef
import dk.cachet.carp.analytics.application.plan.SystemEnvironmentRef
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Three-step workflow spanning three different environment types.
 * Fixture: mixed-environment-three-step.yaml
 *
 * Steps:
 *   1. Load Data       — system Python (no setup overhead)
 *   2. Transform Data  — R (fast setup)
 *   3. Finalize Data   — Pixi Python (no extra packages, fast relative to conda)
 *
 * Validates cross-environment data handoff and that each environment
 * type is correctly provisioned within the same workflow execution.
 */
class MixedEnvironmentThreeStepTest : IntegrationTestBase()
{
    private lateinit var plan: ExecutionPlan

    @BeforeTest
    fun setupTestData()
    {
        val descriptor = loadFixture( "mixed-environment-three-step.yaml" )
        val definition = WorkflowDescriptorImporter().import( descriptor )
        plan = generateExecutionPlan( definition )

        val root = executionRootFor( plan )
        setupWorkspaceAssets(
            root,
            "scripts/step1_load.py" to "scripts/step1_load.py",
            "scripts/step2_transform.R" to "scripts/step2_transform.R",
            "scripts/step3_finalize.py" to "scripts/step3_finalize.py",
            "data/input.csv" to "inputs/input.csv"
        )
    }

    @Test
    fun `mixed environment pipeline executes successfully`()
    {
        // Plan structure
        assertPlanValid( plan )
        assertPlanHasSteps( plan, expectedCount = 3 )
        assertStepsOrdered( plan, listOf( "Load Data", "Transform Data", "Finalize Data" ) )

        // All three environment types are present
        val envRefs = plan.requiredEnvironmentRefs.values
        assertTrue( envRefs.any { it is SystemEnvironmentRef }, "Plan should have a system environment" )
        assertTrue( envRefs.any { it is REnvironmentRef }, "Plan should have an R environment" )
        assertTrue( envRefs.any { it is PixiEnvironmentRef }, "Plan should have a Pixi environment" )

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
        assertEquals( 3, report.stepResults.size )
        report.stepResults.forEach { step ->
            assertEquals( ExecutionStatus.SUCCEEDED, step.status )
            assertTrue( step.finishedAt!! >= step.startedAt!! )
        }

        val root = executionRootFor( plan )

        // Step 1 (system Python): loaded-csv has row_id column
        val loadedFile = root.resolve( "steps/01_load_data/outputs/loaded-csv.csv" )
        assertTrue( loadedFile.exists(), "Step 1 output should exist at $loadedFile" )
        val loadedContent = loadedFile.readText()
        assertTrue( loadedContent.contains( "row_id" ), "Step 1 output should have row_id column" )
        assertTrue( loadedContent.contains( "col1" ), "Step 1 output should preserve original columns" )

        // Step 2 (R): values are uppercased
        val transformedFile = root.resolve( "steps/02_transform_data/outputs/transformed-csv.csv" )
        assertTrue( transformedFile.exists(), "Step 2 output should exist at $transformedFile" )
        val transformedContent = transformedFile.readText()
        assertTrue( transformedContent.contains( "VALUE1" ), "Step 2 output should have uppercased values" )

        // Step 3 (Pixi Python): status column added
        val finalFile = root.resolve( "steps/03_finalize_data/outputs/final-csv.csv" )
        assertTrue( finalFile.exists(), "Step 3 output should exist at $finalFile" )
        val finalContent = finalFile.readText()
        assertTrue( finalContent.contains( "status" ), "Step 3 output should have status column" )
        assertTrue( finalContent.contains( "done" ), "Step 3 output should have status = done" )
    }
}
