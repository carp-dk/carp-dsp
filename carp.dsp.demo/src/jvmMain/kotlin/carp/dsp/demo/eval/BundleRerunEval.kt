package carp.dsp.demo.eval

import carp.dsp.core.application.authoring.resolve.StepLibrary
import carp.dsp.core.application.run.WorkflowExecutor
import carp.dsp.core.application.run.WorkflowPackage
import carp.dsp.demo.io.DemoIo
import carp.dsp.demo.io.DemoRun
import carp.dsp.demo.io.MobgapScripts
import carp.dsp.steps.ClasspathStepLibrary
import dk.cachet.carp.analytics.application.execution.ExecutionStatus
import dk.cachet.carp.common.application.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs

/**
 * SH1 eval: can the gait run be executed again from its bundle alone, on another machine?
 *
 *   make    - runs the locked gait workflow here; its run folder is the bundle
 *             (workflow.yaml, scripts, lock file, plan, report, environment records,
 *             RO-Crate metadata), copied to build/bundle-rerun/bundle
 *   rerun   - (in a Linux container) checks the bundle against its own RO-Crate hashes,
 *             runs it again from that folder alone, with an empty step library, and
 *             compares every output CSV with the bundle's
 *   report  - writes bundle-rerun-numbers.tex (to the paper with -PpaperDir)
 *
 *   ./gradlew :carp.dsp.demo:evalBundleRerunAll -PpaperDir=../carp-dsp-sys-paper
 */
fun main( args: Array<String> )
{
    when ( args.firstOrNull() )
    {
        "make" -> make()
        "rerun" -> rerun( File( args.getOrNull( 1 ) ?: error( "rerun needs the bundle folder" ) ) )
        "report" -> report()
        else -> error( "Usage: make | rerun <bundle> | report" )
    }
}

private fun bundleHome(): File = DemoIo.projectRoot().resolve( "build/bundle-rerun" )

private fun platform() = "${System.getProperty( "os.name" )} ${System.getProperty( "os.arch" )}"

private fun make()
{
    val work = DemoRun.freshResultsDir( bundleHome().resolve( "origin" ).toPath() )
    val loaded = DemoRun.loadWorkflow( "workflows/mobgap-gait-analysis.yaml", work )
    val executor = WorkflowExecutor.filesystem( ClasspathStepLibrary(), work )
    val prepared = executor.prepare( loaded.source, loaded.descriptor )
    val runId = UUID.randomUUID()
    val runRoot = MobgapScripts.runRoot( work, prepared.plan, runId ).toFile()
    MobgapScripts.stage( runRoot.toPath() )
    val report = executor.run( prepared, runId )
    check( report.status == ExecutionStatus.SUCCEEDED ) { "Gait run failed: " + report.issues.joinToString { it.message } }

    val bundle = bundleHome().resolve( "bundle" ).apply { deleteRecursively() }
    runRoot.copyRecursively( bundle )
    bundleHome().resolve( "origin-platform.txt" ).writeText( platform() )
    val files = bundle.walkTopDown().count { it.isFile }
    println( "Bundle: $files files, ${bundle.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1024} KiB at ${bundle.absolutePath}" )
}

private fun rerun( bundle: File )
{
    // 1. The bundle is intact: every file its RO-Crate lists has the recorded SHA-256.
    val crate = Json.parseToJsonElement( bundle.resolve( "ro-crate-metadata.json" ).readText() ).jsonObject
    val listed = crate.getValue( "@graph" ).jsonArray.map { it.jsonObject }
        .filter { it.containsKey( "sha256" ) }
    val intact = listed.count { sha256( bundle.resolve( it.str( "@id" ) ).readBytes() ) == it.str( "sha256" ) }

    // 2. Run it again from the bundle alone: an empty step library, a fresh workspace.
    val work = DemoRun.freshResultsDir( bundleHome().resolve( "rerun" ).toPath() )
    val noLibrary = StepLibrary { _, _ -> null }
    val executor = WorkflowExecutor.filesystem( noLibrary, work )
    val prepared = executor.prepare( WorkflowPackage.ofBundle( bundle.toPath() ) )
    val runId = UUID.randomUUID()
    val report = executor.run( prepared, runId )
    val runRoot = MobgapScripts.runRoot( work, prepared.plan, runId ).toFile()

    // 3. Same outputs: each CSV byte-identical (line endings normalised), or how far apart.
    val originals = csvs( bundle )
    val again = csvs( runRoot )
    val rows = originals.map { ( rel, f ) ->
        val other = again[ rel ]
        val same = other != null && normalised( f ) == normalised( other )
        Triple( rel, same, if ( same || other == null ) null else maxNumericDiff( f, other ) )
    }

    val out = DemoIo.evalResultsDir().resolve( "bundle-rerun.csv" )
    out.writeText(
        buildString {
            appendLine( "origin,rerun,files_listed,files_intact,rerun_status,file,identical,max_abs_diff" )
            val origin = bundleHome().resolve( "origin-platform.txt" ).takeIf { it.isFile }?.readText() ?: "unknown"
            rows.forEach { ( rel, same, diff ) ->
                appendLine( "$origin,${platform()},${listed.size},$intact,${report.status},$rel,$same,${diff ?: ""}" )
            }
        }
    )
    println( "Bundle intact: $intact / ${listed.size} files match their RO-Crate SHA-256" )
    println( "Re-run from the bundle on ${platform()}: ${report.status}" )
    rows.forEach { ( rel, same, diff ) -> println( "  ${if ( same ) "same   " else "differs"} $rel${diff?.let { "  (max diff ${"%.1e".format( Locale.ROOT, it )})" }.orEmpty()}" ) }
}

private fun report()
{
    val rows = DemoIo.evalResultsDir().resolve( "bundle-rerun.csv" ).readLines().drop( 1 ).filter { it.isNotBlank() }.map { it.split( "," ) }
    check( rows.isNotEmpty() ) { "No bundle-rerun.csv: run make and rerun first" }
    val first = rows.first()
    val numbers = DemoIo.evalResultsDir().resolve( "bundle-rerun-numbers.tex" )
    numbers.writeText(
        buildString {
            appendLine( "% GENERATED by BundleRerunEval - do not edit; run :carp.dsp.demo:evalBundleRerunAll" )
            fun macro( name: String, value: Any ) = appendLine( "\\newcommand{\\$name}{$value}" )
            macro( "BundleOrigin", first[ 0 ] )
            macro( "BundleRerunOn", first[ 1 ] )
            macro( "BundleFilesListed", first[ 2 ] )
            macro( "BundleFilesIntact", first[ 3 ] )
            macro( "BundleRerunSucceeded", if ( first[ 4 ] == "SUCCEEDED" ) "yes" else "no" )
            macro( "BundleOutputs", rows.size )
            macro( "BundleOutputsIdentical", rows.count { it[ 6 ] == "true" } )
            macro( "BundleMaxAbsDiff", rows.mapNotNull { it[ 7 ].toDoubleOrNull() }.maxOrNull()?.let { "%.1e".format( Locale.ROOT, it ) } ?: "0" )
        }
    )
    println( numbers.readText() )
    DemoIo.publishToPaper( numbers, "generated/bundle-rerun-numbers.tex" )
}

private fun JsonObject.str( key: String ) = getValue( key ).jsonPrimitive.content

private fun csvs( root: File ): Map<String, File> =
    root.resolve( "steps" ).walkTopDown()
        .filter { it.isFile && it.extension == "csv" && it.parentFile.name == "outputs" }
        .associateBy { it.relativeTo( root ).invariantSeparatorsPath }

private fun normalised( f: File ): String = f.readText().replace( "\r\n", "\n" )

private fun maxNumericDiff( a: File, b: File ): Double? {
    var max: Double? = null
    a.readLines().zip( b.readLines() ).forEach { ( x, y ) ->
        x.split( "," ).zip( y.split( "," ) ).forEach { ( p, q ) ->
            val dp = p.toDoubleOrNull()
            val dq = q.toDoubleOrNull()
            if ( dp != null && dq != null ) max = maxOf( max ?: 0.0, abs( dp - dq ) )
        }
    }
    return max
}

private fun sha256( bytes: ByteArray ): String =
    MessageDigest.getInstance( "SHA-256" ).digest( bytes ).joinToString( "" ) { "%02x".format( it ) }
