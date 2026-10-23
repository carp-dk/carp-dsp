package carp.dsp.demo.io

import dk.cachet.carp.analytics.application.execution.workspace.WorkspacePathFormatter
import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import dk.cachet.carp.common.application.UUID
import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * Stages the mobgap gait pipeline's scripts for a run.
 *
 * The workflow's steps call `scripts/mobgap/<script>.py` relative to the run's
 * execution root, which is the working directory a step's command runs in, so the
 * scripts must be copied there before the run starts.
 */
object MobgapScripts
{
    /** The eight scripts the gait workflows call. */
    val SCRIPTS: List<String> = listOf(
        "import_data.py", "gsd.py", "icd.py", "per_sec_params.py",
        "wba.py", "aggregate.py", "plot_wb_params.py", "plot_aggregated_dmos.py"
    )

    /** Returns the execution root a run of [plan] with [runId] uses under [workspaceRoot]. */
    fun runRoot( workspaceRoot: Path, plan: ExecutionPlan, runId: UUID ): Path =
        workspaceRoot
            .resolve( WorkspacePathFormatter.formatWorkflowName( plan.workflowName ) )
            .resolve( "run_$runId" )

    /** Copies [SCRIPTS] to `scripts/mobgap` under [runRoot], creating directories as needed. */
    fun stage( runRoot: Path )
    {
        val scriptsDir = runRoot.resolve( "scripts/mobgap" ).createDirectories()
        SCRIPTS.forEach { DemoIo.copyResource( "scripts/mobgap/$it", scriptsDir.resolve( it ) ) }
    }
}
