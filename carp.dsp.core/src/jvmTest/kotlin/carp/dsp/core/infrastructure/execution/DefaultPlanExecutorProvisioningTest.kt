package carp.dsp.core.infrastructure.execution

import carp.dsp.core.infrastructure.execution.workspace.FileSource
import carp.dsp.core.infrastructure.execution.workspace.WorkspaceProvisioning
import carp.dsp.core.testing.StubArtefactStore
import carp.dsp.core.testing.StubCommandRunner
import dk.cachet.carp.analytics.application.execution.workspace.ExecutionWorkspace
import dk.cachet.carp.analytics.application.execution.workspace.StepInfo
import dk.cachet.carp.analytics.application.execution.workspace.WorkspaceManager
import dk.cachet.carp.analytics.application.plan.CommandSpec
import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import dk.cachet.carp.analytics.application.plan.ExpandedArg
import dk.cachet.carp.analytics.application.plan.PlannedStep
import dk.cachet.carp.analytics.application.plan.ResolvedBindings
import dk.cachet.carp.analytics.domain.workflow.StepMetadata
import dk.cachet.carp.common.application.UUID
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers the executor's provisioning step: the files a resolved step needs are
 * materialised into the run workspace before any step runs.
 *
 * Separate from [DefaultPlanExecutorTest] because it needs a workspace on a real
 * filesystem - the rest of the executor's behaviour is exercised against test
 * doubles that never touch disk.
 */
class DefaultPlanExecutorProvisioningTest
{
    /** Workspace manager whose execution root is a real, absolute temp directory. */
    private class TempWorkspaceManager( private val root: Path ) : WorkspaceManager
    {
        override fun create( plan: ExecutionPlan, runId: UUID ) = ExecutionWorkspace(
            runId = runId,
            executionRoot = root.toString(),
            workflowName = plan.workflowName,
            stepInfos = plan.steps.associate {
                it.metadata.id to StepInfo( it.metadata.id, it.metadata.name, 0 )
            }
        )

        override fun prepareStepDirectories( workspace: ExecutionWorkspace, stepId: UUID )
        {
            // Nothing to do: the temp root already exists, and provisioning creates
            // whatever parent directories a staged file needs.
        }

        override fun resolveStepWorkingDir( workspace: ExecutionWorkspace, stepId: UUID ) = root.toString()
    }

    private fun plannedStep( name: String, stepId: UUID ) = PlannedStep(
        metadata = StepMetadata( id = stepId, name = name ),
        process = CommandSpec( "echo", listOf( ExpandedArg.Literal( name ) ) ),
        bindings = ResolvedBindings(),
        environmentRef = null,
    )

    private fun plan( vararg steps: PlannedStep ) = ExecutionPlan(
        workflowName = "wf-provisioning",
        planId = UUID.randomUUID().toString(),
        steps = steps.toList(),
    )

    @Test
    fun `run provisions a step's files into the workspace before executing`()
    {
        val root = Files.createTempDirectory( "exec-provision" )
        val source = Files.createTempFile( "raw", ".csv" ).apply { writeText( "a,b\n1,2\n" ) }
        val stepId = UUID.randomUUID()

        val executor = DefaultPlanExecutor(
            workspaceManager = TempWorkspaceManager( root ),
            artefactStore = StubArtefactStore(),
            options = DefaultPlanExecutor.Options( commandRunner = StubCommandRunner() ),
        )
        val provisioning = WorkspaceProvisioning(
            mapOf(
                stepId to mapOf(
                    "impl/python/clean.py" to FileSource.Content( "print('clean')\n" ),
                    "raw.csv" to FileSource.Copy( source ),
                )
            )
        )

        executor.run( plan( plannedStep( "clean", stepId ) ), UUID.randomUUID(), provisioning )

        assertEquals( "print('clean')\n", root.resolve( "impl/python/clean.py" ).readText() )
        assertEquals( "a,b\n1,2\n", root.resolve( "raw.csv" ).readText() )
    }
}
