package carp.dsp.core.integration

import dk.cachet.carp.analytics.application.execution.ExecutionReport
import dk.cachet.carp.analytics.application.execution.ExecutionStatus
import dk.cachet.carp.analytics.infrastructure.execution.EnvironmentExecutionLogs
import dk.cachet.carp.common.application.UUID
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

class IntegrationTestBaseTest : IntegrationTestBase()
{
    @Test
    fun `fixture loading works`()
    {
        val descriptor = loadFixture( "minimal-valid.yaml" )
        assertNotNull( descriptor )
        assertNotNull( descriptor.metadata )
    }

    @Test
    fun `base class setup completes without error`()
    {
        assertTrue( isTmpDirInitialized() )
        assertTrue( isOrchestratorInitialized() )
        assertTrue( isArtefactStoreInitialized() )
        assertTrue( isExecutorInitialized() )
        assertTrue( isWorkspaceManagerInitialized() )
        assertTrue( isEnvRegistryInitialized() )
        assertTrue( isRunIdInitialized() )
    }

    @Test
    fun `run ID is set before test body executes`()
    {
        assertNotNull( runId )
        val captured = runId
        assertEquals( captured, runId )
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun `assertion helpers work`()
    {
        // Just verify they don't throw
        val emptyReport = ExecutionReport(
            planId = UUID.randomUUID(),
            runId = UUID.randomUUID(),
            status = ExecutionStatus.SUCCEEDED,
            issues = emptyList(),
            startedAt = Clock.System.now(),
            finishedAt = Clock.System.now(),
            stepResults = emptyList(),
            environmentLogs = EnvironmentExecutionLogs()
        )

        assertExecutionSucceeded( emptyReport )
    }

    @Test
    fun `test double factories work`()
    {
        val stubRunner = createStubCommandRunner()
        assertNotNull( stubRunner )

        val recordingWsm = createRecordingWorkspaceManager()
        assertNotNull( recordingWsm )
    }
}
