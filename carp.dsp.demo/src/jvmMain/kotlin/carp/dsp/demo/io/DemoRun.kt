package carp.dsp.demo.io

import carp.dsp.core.application.run.PreparedWorkflow
import carp.dsp.core.application.run.WorkflowExecutor
import carp.dsp.core.application.run.WorkflowSource
import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor
import carp.dsp.core.infrastructure.serialization.WorkflowYamlCodec
import dk.cachet.carp.analytics.application.execution.ExecutionReport
import dk.cachet.carp.analytics.application.execution.ExecutionStatus
import dk.cachet.carp.common.application.UUID
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * The console choreography every workflow demo repeats: a banner, a clean results
 * directory, a workflow read out of resources and written where the runner expects
 * it, and a run whose failures are reported the same way each time.
 */
object DemoRun
{
    /** Width of the rules a demo draws. */
    const val WIDTH: Int = 70

    /**
     * A workflow, ready to prepare.
     *
     * [descriptor] is handed on so a caller does not parse twice; [source] is what
     * [WorkflowExecutor] needs, since resolution reads and writes the `steps.lock`
     * beside the workflow file.
     */
    data class LoadedWorkflow(
        val source: WorkflowSource,
        val descriptor: WorkflowDescriptor,
        val yaml: String,
    )

    /** A titled banner, e.g. `====` / title / `====` / blank. */
    fun banner( title: String, width: Int = WIDTH )
    {
        println( "=".repeat( width ) )
        println( title )
        println( "=".repeat( width ) )
        println()
    }

    /** A rule, for opening and closing a run's step output. */
    fun rule( width: Int = WIDTH ) = println( "-".repeat( width ) )

    /** A heavier rule, matching [banner]. */
    fun divider( width: Int = WIDTH ) = println( "=".repeat( width ) )

    /**
     * Empties [dir], deleting anything a previous run left, and returns it.
     *
     * Demos are meant to be re-runnable and to show only what this run produced.
     */
    @OptIn( ExperimentalPathApi::class )
    fun freshResultsDir( dir: Path ): Path
    {
        if ( dir.exists() )
        {
            println( "Cleaning up previous results..." )
            dir.deleteRecursively()
        }
        dir.createDirectories()
        return dir
    }

    /**
     * Reads the workflow at [resourcePath] and writes it into [resultsDir], which is
     * where its `steps.lock` will be written and what its relative inputs resolve
     * against.
     */
    fun loadWorkflow( resourcePath: String, resultsDir: Path ): LoadedWorkflow
    {
        val yaml = DemoIo.loadResource( resourcePath )
        val descriptor = WorkflowYamlCodec().decodeOrThrow( yaml )
        println( "Workflow loaded: ${descriptor.metadata.name}" )

        val fileName = resourcePath.substringAfterLast( '/' )
        val file = resultsDir.resolve( fileName )
        file.writeText( yaml )

        return LoadedWorkflow( WorkflowSource( resultsDir, fileName ), descriptor, yaml )
    }

    /**
     * Runs [prepared] between two rules and reports the outcome.
     *
     * Returns null when the run did not succeed, having printed the status and every
     * issue - so a caller can `?: return` instead of repeating the check.
     */
    fun execute(
        executor: WorkflowExecutor,
        prepared: PreparedWorkflow,
        runId: UUID,
        width: Int = WIDTH,
        notice: String? = null,
    ): ExecutionReport?
    {
        println()
        println( "Executing workflow..." )
        notice?.let { println( it ) }
        rule( width )
        val report = executor.run( prepared, runId )
        rule( width )

        if ( report.status != ExecutionStatus.SUCCEEDED )
        {
            println( "Workflow execution failed: ${report.status}" )
            report.issues.forEach { println( "   - ${it.message}" ) }
            return null
        }

        println( "Workflow execution succeeded" )
        println()
        return report
    }
}
