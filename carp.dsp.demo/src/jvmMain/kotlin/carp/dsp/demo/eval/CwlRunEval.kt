package carp.dsp.demo.eval

import carp.dsp.core.application.run.WorkflowExecutor
import carp.dsp.core.application.run.WorkflowPackage
import carp.dsp.core.application.translation.cwl.DspToCwlExporter
import carp.dsp.core.infrastructure.execution.handlers.EnvironmentStore
import carp.dsp.demo.io.DemoIo
import carp.dsp.demo.io.DemoRun
import carp.dsp.demo.io.MobgapScripts
import carp.dsp.steps.ClasspathStepLibrary
import dk.cachet.carp.analytics.application.execution.ExecutionStatus
import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import dk.cachet.carp.analytics.application.plan.PixiEnvironmentRef
import dk.cachet.carp.common.application.UUID
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * SH2 eval: does the CWL export run in a CWL engine and give CARP-DSP's results?
 *
 *   run <cwltool>  - (in a Linux container; cwltool does not run on Windows) runs the locked
 *                    gait workflow through CARP-DSP, exports it as one CWL Workflow into its
 *                    package folder, checks it with `cwltool --validate`, runs it with
 *                    `cwltool --no-container` inside the same locked pixi environment, and
 *                    compares every output CSV with CARP-DSP's
 *   report         - writes cwl-run-numbers.tex (to the paper with -PpaperDir)
 *
 *   ./gradlew :carp.dsp.demo:evalCwlRunAll -PpaperDir=../carp-dsp-sys-paper
 */
fun main( args: Array<String> )
{
    when ( args.firstOrNull() )
    {
        "run" -> run( File( args.getOrNull( 1 ) ?: error( "run needs the cwltool executable" ) ) )
        "report" -> report()
        else -> error( "Usage: run <cwltool> | report" )
    }
}

private fun cwlHome(): File = DemoIo.projectRoot().resolve( "build/cwl-run" )

private fun cwlRunResults(): File = DemoIo.evalResultsDir().resolve( "cwl-run.csv" )

// Proxy and certificate settings, if any: the import step downloads mobgap's example data,
// and cwltool otherwise hides the caller's environment from a tool.
private val PASS_THROUGH = listOf(
    "HTTPS_PROXY", "HTTP_PROXY", "https_proxy", "http_proxy", "NO_PROXY", "SSL_CERT_FILE", "REQUESTS_CA_BUNDLE"
)

private fun run( cwltool: File )
{
    // 1. The CARP-DSP run: its outputs are the reference, its package folder the CWL's home.
    val work = DemoRun.freshResultsDir( cwlHome().resolve( "carp-dsp" ).toPath() )
    val loaded = DemoRun.loadWorkflow( "workflows/mobgap-gait-analysis.yaml", work )
    val executor = WorkflowExecutor.filesystem( ClasspathStepLibrary(), work )
    val prepared = executor.prepare( loaded.source, loaded.descriptor )
    val runId = UUID.randomUUID()
    val runRoot = MobgapScripts.runRoot( work, prepared.plan, runId ).toFile()
    MobgapScripts.stage( runRoot.toPath() )
    val report = executor.run( prepared, runId )
    check( report.status == ExecutionStatus.SUCCEEDED ) { "Gait run failed: " + report.issues.joinToString { it.message } }

    // 2. The package: the workflow's own files (scripts, lock file) plus workflow.cwl.
    val pkg = cwlHome().resolve( "package" ).apply { deleteRecursively(); mkdirs() }
    WorkflowPackage.ofBundle( runRoot.toPath() ).files.forEach { ( rel, path ) -> path.toFile().copyTo( pkg.resolve( rel ) ) }
    runRoot.resolve( "workflow.yaml" ).copyTo( pkg.resolve( "workflow.yaml" ) )
    val cwl = checkNotNull( DspToCwlExporter.exportWorkflow( prepared.descriptor ) ) { "No CWL exported" }
    pkg.resolve( "workflow.cwl" ).writeText( cwl.content )

    // 3. Validate and run it in cwltool, with the locked environment's python first on PATH.
    val version = capture( listOf( cwltool.path, "--version" ), pkg ).trim().substringAfterLast( ' ' )
    val valid = exec( listOf( cwltool.path, "--validate", "workflow.cwl" ), pkg, "validate" ) == 0
    val out = cwlHome().resolve( "cwl-out" ).apply { deleteRecursively() }
    val preserve = PASS_THROUGH.filter { System.getenv( it ) != null }.flatMap { listOf( "--preserve-environment", it ) }
    val command = listOf( cwltool.path, "--no-container" ) + preserve + listOf( "--outdir", out.path, "workflow.cwl" )
    val ran = exec( command, pkg, "run", gaitEnvBin( prepared.plan, runRoot ) ) == 0

    // 4. Same outputs: each CSV byte-identical (line endings normalised), or how far apart.
    val rows = cwlRunCsvs( runRoot ).map { ( name, ref ) ->
        val other = out.resolve( name ).takeIf { it.isFile }
        val same = other != null && ref.readText().replace( "\r\n", "\n" ) == other.readText().replace( "\r\n", "\n" )
        Triple( name, same, if ( same || other == null ) null else cwlMaxDiff( ref, other ) )
    }
    writeRows( version, valid, ran, prepared.plan.steps.size, rows )
}

private fun writeRows( version: String, valid: Boolean, ran: Boolean, steps: Int, rows: List<Triple<String, Boolean, Double?>> )
{
    val on = "${System.getProperty( "os.name" )} ${System.getProperty( "os.arch" )}"
    cwlRunResults().writeText(
        buildString {
            appendLine( "platform,cwltool,steps,validated,cwl_run,file,identical,max_abs_diff" )
            rows.forEach { ( name, same, diff ) -> appendLine( "$on,$version,$steps,$valid,$ran,$name,$same,${diff ?: ""}" ) }
        }
    )
    println( "cwltool $version on $on: validate ${if ( valid ) "ok" else "FAILED"}, run ${if ( ran ) "ok" else "FAILED"}" )
    rows.forEach { ( name, same, diff ) -> println( "  ${if ( same ) "same   " else "differs"} $name${diff?.let { "  (max diff $it)" }.orEmpty()}" ) }
}

private fun report()
{
    val rows = cwlRunResults().readLines().drop( 1 ).filter { it.isNotBlank() }.map { it.split( "," ) }
    check( rows.isNotEmpty() ) { "No cwl-run.csv: run evalCwlRunLinux first" }
    val first = rows.first()
    val numbers = DemoIo.evalResultsDir().resolve( "cwl-run-numbers.tex" )
    numbers.writeText(
        buildString {
            appendLine( "% GENERATED by CwlRunEval - do not edit; run :carp.dsp.demo:evalCwlRunAll" )
            fun macro( name: String, value: Any ) = appendLine( "\\newcommand{\\$name}{$value}" )
            macro( "CwlRunOn", first[ 0 ] )
            macro( "CwlToolVersion", first[ 1 ] )
            macro( "CwlSteps", first[ 2 ] )
            macro( "CwlValid", if ( first[ 3 ] == "true" ) "yes" else "no" )
            macro( "CwlRunSucceeded", if ( first[ 4 ] == "true" ) "yes" else "no" )
            macro( "CwlOutputs", rows.size )
            macro( "CwlOutputsIdentical", rows.count { it[ 6 ] == "true" } )
            val max = rows.mapNotNull { it[ 7 ].toDoubleOrNull() }.maxOrNull()
            macro( "CwlMaxAbsDiff", max?.let { "%.1e".format( Locale.ROOT, it ) } ?: "0" )
        }
    )
    println( numbers.readText() )
    DemoIo.publishToPaper( numbers, "generated/cwl-run-numbers.tex" )
}

// The pixi environment CARP-DSP ran the workflow in, found as the run found it (lock under the run root).
private fun gaitEnvBin( plan: ExecutionPlan, runRoot: File ): File
{
    val ref = plan.requiredEnvironmentRefs.values.filterIsInstance<PixiEnvironmentRef>().single()
    val locked = ref.copy( lockFile = ref.lockFile?.let { runRoot.resolve( it ).absolutePath } )
    val env = checkNotNull( EnvironmentStore.resolve( locked ) ) { "No pixi environment for ${ref.name}" }
    return env.directory.resolve( ".pixi/envs/default/bin" ).toFile()
}

private fun exec( command: List<String>, dir: File, log: String, pathFirst: File? = null ): Int
{
    val builder = ProcessBuilder( command ).directory( dir ).redirectErrorStream( true )
        .redirectOutput( cwlHome().resolve( "cwltool-$log.log" ) )
    pathFirst?.let { builder.environment()[ "PATH" ] = "${it.absolutePath}${File.pathSeparator}${System.getenv( "PATH" )}" }
    return builder.start().waitFor()
}

private fun capture( command: List<String>, dir: File ): String =
    ProcessBuilder( command ).directory( dir ).redirectErrorStream( true ).start()
        .let { p -> p.inputStream.bufferedReader().readText().also { p.waitFor() } }

private fun cwlRunCsvs( root: File ): Map<String, File> =
    root.resolve( "steps" ).walkTopDown()
        .filter { it.isFile && it.extension == "csv" && it.parentFile.name == "outputs" }
        .sortedBy { it.relativeTo( root ).invariantSeparatorsPath }
        .associateBy { it.name }

private fun cwlMaxDiff( a: File, b: File ): Double? =
    a.readLines().zip( b.readLines() ).flatMap { ( x, y ) ->
        x.split( "," ).zip( y.split( "," ) ).mapNotNull { ( p, q ) ->
            val dp = p.toDoubleOrNull()
            val dq = q.toDoubleOrNull()
            if ( dp != null && dq != null ) abs( dp - dq ) else null
        }
    }.maxOrNull()
