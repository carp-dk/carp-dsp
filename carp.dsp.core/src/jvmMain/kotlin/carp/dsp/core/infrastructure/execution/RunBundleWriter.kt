package carp.dsp.core.infrastructure.execution

import dk.cachet.carp.analytics.application.execution.ExecutionReport
import dk.cachet.carp.analytics.application.execution.ExecutionStatus
import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import dk.cachet.carp.analytics.domain.data.FileLocation
import dk.cachet.carp.analytics.infrastructure.serialization.CoreAnalyticsSerializer
import dk.cachet.carp.common.infrastructure.serialization.createDefaultJSON
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/**
 * Turns a finished run folder into a self-contained bundle.
 *
 * Writes `plan.json` and `report.json` (the plan and the run report) and
 * `ro-crate-metadata.json`, a Workflow Run RO-Crate describing the folder: the workflow
 * (`workflow.yaml`, staged at the start of the run) as main entity, and one run with
 * its start and end time, status, input files and output files (with size and SHA-256).
 * Together with the staged scripts, inputs, lock files and `environments/` records, the
 * folder is what another machine needs to run the workflow again.
 */
internal object RunBundleWriter
{
    /** Files of a bundle that the run itself writes; a re-run from the bundle leaves them out. */
    val RUN_OUTPUTS: Set<String> = setOf( "steps", "logs", "environments", PLAN, REPORT, CRATE )

    private const val PLAN = "plan.json"
    private const val REPORT = "report.json"
    private const val CRATE = "ro-crate-metadata.json"
    private const val WORKFLOW = "workflow.yaml"

    private const val LANGUAGE = "#carp-dsp"

    private val PROFILES = listOf(
        "https://w3id.org/ro/wfrun/process/0.5",
        "https://w3id.org/ro/wfrun/workflow/0.5",
        "https://w3id.org/workflowhub/workflow-ro-crate/1.0"
    )
    private val WORKFLOW_TYPES = listOf( "File", "SoftwareSourceCode", "ComputationalWorkflow" )

    // CARP's default JSON plus the analytics types (a plan's task runs are polymorphic).
    private val json = Json( createDefaultJSON( CoreAnalyticsSerializer.module ) ) {
        prettyPrint = true
        encodeDefaults = true
    }

    /** Writes the bundle files into [root]; does nothing when [root] is not a directory. */
    fun write( root: File, plan: ExecutionPlan, report: ExecutionReport )
    {
        if ( !root.isDirectory ) return
        root.resolve( PLAN ).writeText( json.encodeToString( ExecutionPlan.serializer(), plan ) )
        root.resolve( REPORT ).writeText( json.encodeToString( ExecutionReport.serializer(), report ) )
        root.resolve( CRATE ).writeText( json.encodeToString( JsonObject.serializer(), crate( root, plan, report ) ) )
    }

    private fun crate( root: File, plan: ExecutionPlan, report: ExecutionReport ): JsonObject
    {
        val files = root.walkTopDown().filter { it.isFile && it.name != CRATE }
            .map { it.relativeTo( root ).invariantSeparatorsPath }.sorted().toList()
        val runId = "#run-${report.runId}"
        val graph = buildJsonArray {
            add( metadataEntity() )
            add( rootEntity( plan, report, files, runId ) )
            add( workflowEntity( plan ) )
            add( languageEntity() )
            add( runEntity( plan, report, files, runId ) )
            files.forEach { add( fileEntity( root, it ) ) }
        }
        return buildJsonObject {
            put( "@context", "https://w3id.org/ro/crate/1.1/context" )
            put( "@graph", graph )
        }
    }

    private fun metadataEntity() = buildJsonObject {
        put( "@id", CRATE )
        put( "@type", "CreativeWork" )
        put( "conformsTo", ref( "https://w3id.org/ro/crate/1.1" ) )
        put( "about", ref( "./" ) )
    }

    private fun rootEntity( plan: ExecutionPlan, report: ExecutionReport, files: List<String>, runId: String ) =
        buildJsonObject {
            put( "@id", "./" )
            put( "@type", "Dataset" )
            put( "name", "Run of ${plan.workflowName}" )
            report.finishedAt?.let { put( "datePublished", it.toString() ) }
            put( "conformsTo", JsonArray( PROFILES.map( ::ref ) ) )
            put( "mainEntity", ref( WORKFLOW ) )
            put( "mentions", ref( runId ) )
            put( "hasPart", JsonArray( files.map( ::ref ) ) )
        }

    private fun workflowEntity( plan: ExecutionPlan ) = buildJsonObject {
        put( "@id", WORKFLOW )
        put( "@type", JsonArray( WORKFLOW_TYPES.map( ::JsonPrimitive ) ) )
        put( "name", plan.workflowName )
        put( "programmingLanguage", ref( LANGUAGE ) )
    }

    private fun languageEntity() = buildJsonObject {
        put( "@id", LANGUAGE )
        put( "@type", "ComputerLanguage" )
        put( "name", "CARP-DSP workflow" )
        put( "url", "https://github.com/carp-dk/carp-dsp" )
    }

    private fun runEntity( plan: ExecutionPlan, report: ExecutionReport, files: List<String>, runId: String ) =
        buildJsonObject {
            // Data inputs: the plan's file inputs that are not another step's output.
            val declared = plan.steps.flatMap { it.bindings.inputs.values }
                .filter { it.spec.stepRef == null }
                .mapNotNull { ( it.location as? FileLocation )?.path?.removePrefix( "./" ) }
                .toSet()
            val status =
                if ( report.status == ExecutionStatus.SUCCEEDED ) "CompletedActionStatus" else "FailedActionStatus"
            put( "@id", runId )
            put( "@type", "CreateAction" )
            put( "name", "Run of ${plan.workflowName}" )
            put( "instrument", ref( WORKFLOW ) )
            report.startedAt?.let { put( "startTime", it.toString() ) }
            report.finishedAt?.let { put( "endTime", it.toString() ) }
            put( "actionStatus", ref( "http://schema.org/$status" ) )
            put( "object", JsonArray( files.filter { it in declared }.map( ::ref ) ) )
            put( "result", JsonArray( files.filter { it.startsWith( "steps/" ) && "/outputs/" in it }.map( ::ref ) ) )
        }

    private fun fileEntity( root: File, path: String ) = buildJsonObject {
        val file = root.resolve( path )
        put( "@id", path )
        put( "@type", "File" )
        put( "contentSize", file.length().toString() )
        put( "sha256", sha256( file ) )
    }

    private fun ref( id: String ) = buildJsonObject { put( "@id", id ) }

    private fun sha256( file: File ): String
    {
        val digest = MessageDigest.getInstance( "SHA-256" )
        file.inputStream().use { input ->
            val buffer = ByteArray( BUFFER_BYTES )
            var read = input.read( buffer )
            while ( read > 0 )
            {
                digest.update( buffer, 0, read )
                read = input.read( buffer )
            }
        }
        return digest.digest().joinToString( "" ) { "%02x".format( it ) }
    }

    private const val BUFFER_BYTES = 64 * 1024
}
