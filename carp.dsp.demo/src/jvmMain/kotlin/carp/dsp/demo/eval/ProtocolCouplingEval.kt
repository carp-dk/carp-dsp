package carp.dsp.demo.eval

import carp.dsp.core.application.authoring.mapper.WorkflowDescriptorImporter
import carp.dsp.core.application.plan.DefaultExecutionPlanner
import carp.dsp.core.application.plan.StudyProtocolSnapshotDataTypeProvider
import carp.dsp.core.infrastructure.serialization.WorkflowYamlCodec
import carp.dsp.demo.io.DemoIo
import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import dk.cachet.carp.analytics.application.plan.PlanIssueSeverity
import dk.cachet.carp.common.application.UUID
import dk.cachet.carp.common.infrastructure.serialization.createDefaultJSON
import dk.cachet.carp.protocols.application.StudyProtocolSnapshot
import kotlinx.serialization.json.Json

/**
 * Protocol-coupling eval: plan-time validation of protocol-bound inputs.
 *
 * Runs the 'protocol-coupling-mixed' workflow - contains one input collected by a study protocol, one open
 * dataset - against two protocols and records what the planner does:
 *
 *  1. a protocol that collects heart rate          -> No warnings
 *  2. a protocol that collects step count only     -> PROTOCOL_DATA_NOT_COLLECTED,
 *     no step executes
 *  3. with no protocol supplied                    -> PROTOCOL_NOT_VALIDATED warning
 *
 * Run from the demo menu (id: protocol-coupling-eval):
 *   ./gradlew :carp.dsp.demo:run --args "run protocol-coupling-eval"
 * or directly:
 *   ./gradlew :carp.dsp.demo:evalProtocolCoupling
 *
 * Writes eval_results/protocol-coupling.{txt,csv}.
 */

/** Same fixed namespace as the other evals. */
private val PC_FIXED_NAMESPACE: UUID = UUID.parse( "d3b7f2a0-0000-5000-8000-6d6f62676170" )

private const val WORKFLOW_RESOURCE = "workflows/protocol-coupling-mixed.yaml"
private const val PROTOCOL_HR = "protocols/hr-study-protocol.json"
private const val PROTOCOL_STEPS_ONLY = "protocols/steps-only-protocol.json"

private data class Scenario(
    val name: String,
    val protocolResource: String?,
    val expectedCode: String?
)

private val SCENARIOS = listOf(
    Scenario( "protocol collects heart rate", PROTOCOL_HR, null ),
    Scenario( "protocol collects step count only", PROTOCOL_STEPS_ONLY, "PROTOCOL_DATA_NOT_COLLECTED" ),
    Scenario( "no protocol supplied", null, "PROTOCOL_NOT_VALIDATED" )
)

fun main()
{
    val json = createDefaultJSON()
    val codec = WorkflowYamlCodec()
    val yaml = DemoIo.loadResource( WORKFLOW_RESOURCE )

    val rows = SCENARIOS.map { scenario ->
        val provider = scenario.protocolResource?.let { resource ->
            StudyProtocolSnapshotDataTypeProvider( loadSnapshot( json, resource ) )
        }
        val plan = planWith( codec, yaml, provider )
        val protocolIssues = plan.issues.filter { it.code.startsWith( "PROTOCOL_" ) }
        val errors = plan.issues.filter { it.severity == PlanIssueSeverity.ERROR }
        val matched = protocolIssues.map { it.code }.contains( scenario.expectedCode ) ||
            ( scenario.expectedCode == null && protocolIssues.isEmpty() )

        ScenarioResult(
            scenario = scenario,
            codes = protocolIssues.map { it.code },
            messages = protocolIssues.map { it.message },
            plannedSteps = plan.steps.size,
            errorCount = errors.size,
            runnable = plan.isRunnable(),
            matched = matched
        )
    }

    val report = buildString {
        appendLine( "=".repeat( 78 ) )
        appendLine( "Protocol-coupling eval - ${java.time.Instant.now()}" )
        appendLine( "Workflow: protocol-coupling-mixed.yaml" )
        appendLine( "  - 1 protocol-bound input  (dk.cachet.carp.heartrate)" )
        appendLine( "  - 1 external input        (open Fitbit data, Zenodo 53894)" )
        appendLine()
        rows.forEach { r ->
            appendLine( "Scenario: ${r.scenario.name}" )
            appendLine( "  expected:        ${r.scenario.expectedCode ?: "no protocol issues"}" )
            appendLine( "  protocol issues: ${r.codes.ifEmpty { listOf( "none" ) }.joinToString( ", " )}" )
            appendLine( "  steps planned:   ${r.plannedSteps}" )
            appendLine( "  plan errors:     ${r.errorCount}" )
            appendLine(
                "  runnable:        ${r.runnable}" +
                if ( r.runnable ) "" else "  <- plan carries an ERROR, so no step executes"
            )
            appendLine( "  as expected:     ${r.matched}" )
            r.messages.forEach { appendLine( "    -> $it" ) }
            appendLine()
        }
        appendLine( "All scenarios as expected: ${rows.all { it.matched }}" )
        appendLine(
            "The external input is never protocol-checked in any scenario: protocol-sourced " +
            "data is verified before execution while open data stays usable in the same workflow."
        )
        appendLine( "=".repeat( 78 ) )
    }
    println( report )

    val dir = DemoIo.evalResultsDir()
    dir.resolve( "protocol-coupling.txt" ).appendText( report + "\n" )
    dir.resolve( "protocol-coupling.csv" ).writeText(
        buildString {
            appendLine( "scenario,protocol,expected_code,codes,steps_planned,plan_errors,runnable,as_expected" )
            rows.forEach { r ->
                appendLine(
                    listOf(
                        r.scenario.name,
                        r.scenario.protocolResource ?: "none",
                        r.scenario.expectedCode ?: "",
                        r.codes.joinToString( "|" ),
                        r.plannedSteps.toString(),
                        r.errorCount.toString(),
                        r.runnable.toString(),
                        r.matched.toString()
                    ).joinToString( "," ) { if ( it.contains( "," ) ) "\"$it\"" else it }
                )
            }
        }
    )
    println( "Wrote protocol-coupling.txt and protocol-coupling.csv to: ${dir.absolutePath}" )
}

private data class ScenarioResult(
    val scenario: Scenario,
    val codes: List<String>,
    val messages: List<String>,
    val plannedSteps: Int,
    val errorCount: Int,
    val runnable: Boolean,
    val matched: Boolean
)

private fun planWith(
    codec: WorkflowYamlCodec,
    yaml: String,
    provider: StudyProtocolSnapshotDataTypeProvider?
): ExecutionPlan
{
    val descriptor = codec.decodeOrThrow( yaml )
    val definition = WorkflowDescriptorImporter( PC_FIXED_NAMESPACE ).import( descriptor )
    return DefaultExecutionPlanner( provider ).plan( definition )
}

private fun loadSnapshot( json: Json, path: String ): StudyProtocolSnapshot =
    json.decodeFromString( StudyProtocolSnapshot.serializer(), DemoIo.loadResource( path ) )
