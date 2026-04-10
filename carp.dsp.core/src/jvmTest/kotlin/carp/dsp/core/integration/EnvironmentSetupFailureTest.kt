package carp.dsp.core.integration

import carp.dsp.core.application.authoring.mapper.WorkflowDescriptorImporter
import carp.dsp.core.infrastructure.execution.DefaultPlanExecutor
import carp.dsp.core.testing.ThrowingEnvironmentOrchestrator
import dk.cachet.carp.analytics.application.exceptions.EnvironmentSetupException
import dk.cachet.carp.analytics.application.execution.ExecutionIssueKind
import dk.cachet.carp.analytics.application.execution.ExecutionStatus
import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import dk.cachet.carp.analytics.infrastructure.execution.EnvironmentConfig
import dk.cachet.carp.analytics.infrastructure.execution.SetupTiming
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that an environment setup failure produces a FAILED report with
 * an ORCHESTRATOR_ERROR issue.
 *
 * Setup: a pixi fixture is loaded so the plan references a non-system environment.
 * The plan executor is replaced with one backed by a [ThrowingEnvironmentOrchestrator]
 * that throws [EnvironmentSetupException] during setup. No real pixi install is needed.
 *
 * Fixture: single-step-python-pixi.yaml
 */
class EnvironmentSetupFailureTest : IntegrationTestBase()
{
    private lateinit var plan: ExecutionPlan
    private lateinit var failingExecutor: DefaultPlanExecutor

    @BeforeTest
    fun setupTestData()
    {
        val descriptor = loadFixture( "single-step-python-pixi.yaml" )
        val definition = WorkflowDescriptorImporter().import( descriptor )
        plan = generateExecutionPlan( definition )

        // Build an executor that fails on environment setup.
        // SetupTiming.LAZY ensures setup is attempted per step (not eagerly before all steps).
        val throwingOrchestrator = ThrowingEnvironmentOrchestrator(
            onSetup = true,
            exception = EnvironmentSetupException(
                message = "Simulated environment setup failure",
                envId = "test-env-id"
            )
        )

        failingExecutor = DefaultPlanExecutor(
            workspaceManager = workspaceManager,
            artefactStore = artefactStore,
            options = DefaultPlanExecutor.Options(
                orchestrator = throwingOrchestrator,
                environmentConfig = EnvironmentConfig( setupTiming = SetupTiming.LAZY )
            )
        )
    }

    @Test
    fun `environment setup failure produces FAILED report with ORCHESTRATOR_ERROR issue`()
    {
        val report = failingExecutor.execute( plan, runId )

        // Overall status must be FAILED
        assertEquals(
            ExecutionStatus.FAILED,
            report.status,
            "Report status should be FAILED when environment setup fails"
        )

        // At least one issue must be recorded
        assertTrue( report.issues.isNotEmpty(), "At least one issue should be recorded" )

        // The step itself must be FAILED
        val stepResult = report.stepResults.single()
        assertEquals( ExecutionStatus.FAILED, stepResult.status, "Step status should be FAILED" )

        // Environment failure maps to ORCHESTRATOR_ERROR
        assertIssuesRecorded( report, ExecutionIssueKind.ORCHESTRATOR_ERROR )
    }
}
