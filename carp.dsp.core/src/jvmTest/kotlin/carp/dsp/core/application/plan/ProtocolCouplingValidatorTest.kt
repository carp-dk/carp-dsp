package carp.dsp.core.application.plan

import dk.cachet.carp.analytics.application.plan.PlanIssueSeverity
import dk.cachet.carp.analytics.domain.data.FileFormat
import dk.cachet.carp.analytics.domain.data.FileLocation
import dk.cachet.carp.analytics.domain.data.InputDataSpec
import dk.cachet.carp.analytics.domain.environment.EnvironmentDefinition
import dk.cachet.carp.analytics.domain.tasks.CommandTaskDefinition
import dk.cachet.carp.analytics.domain.workflow.Step
import dk.cachet.carp.analytics.domain.workflow.StepMetadata
import dk.cachet.carp.analytics.domain.workflow.Version
import dk.cachet.carp.analytics.domain.workflow.Workflow
import dk.cachet.carp.analytics.domain.workflow.WorkflowDefinition
import dk.cachet.carp.analytics.domain.workflow.WorkflowMetadata
import dk.cachet.carp.common.application.UUID
import dk.cachet.carp.common.application.data.DataType
import dk.cachet.carp.common.application.tasks.BackgroundTask
import dk.cachet.carp.common.application.tasks.Measure
import dk.cachet.carp.protocols.application.StudyProtocolSnapshot
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

/**
 * Tests for [ProtocolCouplingValidator] and [StudyProtocolSnapshotDataTypeProvider].
 *
 * Covers the F5 acceptance criteria: a protocol-bound input is rejected at plan time
 * when the protocol does not collect its data type, passes when it does, external and
 * step inputs are never checked, and a missing provider degrades to a warning.
 *
 * Note: carp.core-kotlin opts into `kotlin.time.ExperimentalTime` build-wide; carp-dsp
 * does not, so constructing a [StudyProtocolSnapshot] (which carries an [Instant])
 * needs an explicit opt-in here.
 */
@OptIn(ExperimentalTime::class)
class ProtocolCouplingValidatorTest
{
    private val validator = ProtocolCouplingValidator()
    private val protocolId = UUID.parse( "aabbccdd-0000-0000-0000-000000000000" )

    private companion object
    {
        const val HEART_RATE = "dk.cachet.carp.heartrate"
        const val STEP_COUNT = "dk.cachet.carp.stepcount"
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private fun snapshot(
        id: UUID = protocolId,
        version: Int = 1,
        dataTypes: List<String>
    ) = StudyProtocolSnapshot(
        id = id,
        createdOn = Instant.fromEpochSeconds( 0 ),
        version = version,
        ownerId = UUID.randomUUID(),
        name = "Test protocol v$version",
        tasks = setOf(
            BackgroundTask(
                name = "collect",
                measures = dataTypes.map { Measure.DataStream( DataType.fromString( it ) ) }
            )
        )
    )

    private fun protocolInput(
        name: String,
        dataType: String,
        pId: String = protocolId.toString(),
        version: Int? = null
    ): InputDataSpec
    {
        val metadata = buildMap {
            put( "source", "protocol" )
            put( "protocolId", pId )
            put( "dataType", dataType )
            version?.let { put( "protocolVersion", it.toString() ) }
        }
        return InputDataSpec(
            id = UUID.randomUUID(),
            name = name,
            location = FileLocation( path = "", format = FileFormat.UNKNOWN, metadata = metadata )
        )
    }

    private fun externalInput( name: String ): InputDataSpec = InputDataSpec(
        id = UUID.randomUUID(),
        name = name,
        location = FileLocation(
            path = "https://zenodo.org/record/53894",
            format = FileFormat.CSV,
            metadata = mapOf( "source" to "external", "uri" to "https://zenodo.org/record/53894" )
        )
    )

    private fun step(
        name: String,
        inputs: List<InputDataSpec>,
        environmentId: UUID = UUID.randomUUID()
    ) = Step(
        metadata = StepMetadata( id = UUID.randomUUID(), name = name, version = Version( 1, 0 ) ),
        task = CommandTaskDefinition( id = UUID.randomUUID(), name = "task-$name", executable = "echo" ),
        environmentId = environmentId,
        inputs = inputs
    )

    // ── Acceptance criteria ───────────────────────────────────────────────────

    @Test
    fun `rejects protocol input whose data type is not collected`()
    {
        val provider = StudyProtocolSnapshotDataTypeProvider( snapshot( dataTypes = listOf( STEP_COUNT ) ) )
        val steps = listOf( step( "import", listOf( protocolInput( "hr", HEART_RATE ) ) ) )

        val issues = validator.validate( steps, provider )

        assertEquals( 1, issues.size )
        assertEquals( "PROTOCOL_DATA_NOT_COLLECTED", issues[0].code )
        assertEquals( PlanIssueSeverity.ERROR, issues[0].severity )
    }

    @Test
    fun `passes protocol input whose data type is collected`()
    {
        val provider = StudyProtocolSnapshotDataTypeProvider(
            snapshot( dataTypes = listOf( HEART_RATE, STEP_COUNT ) )
        )
        val steps = listOf( step( "import", listOf( protocolInput( "hr", HEART_RATE ) ) ) )

        assertTrue( validator.validate( steps, provider ).isEmpty() )
    }

    @Test
    fun `mixed workflow validates protocol input and ignores external input`()
    {
        val provider = StudyProtocolSnapshotDataTypeProvider( snapshot( dataTypes = listOf( STEP_COUNT ) ) )
        val steps = listOf(
            step(
                "import",
                listOf(
                protocolInput( "hr", HEART_RATE ), // not collected -> ERROR
                externalInput( "open-data" ) // never checked
            )
            )
        )

        val issues = validator.validate( steps, provider )

        assertEquals( 1, issues.size )
        assertEquals( "PROTOCOL_DATA_NOT_COLLECTED", issues[0].code )
    }

    @Test
    fun `unknown protocol yields PROTOCOL_NOT_FOUND`()
    {
        val provider = StudyProtocolSnapshotDataTypeProvider( snapshot( dataTypes = listOf( HEART_RATE ) ) )
        val steps = listOf(
            step(
                "import",
                listOf(
                protocolInput( "hr", HEART_RATE, pId = "ffffffff-0000-0000-0000-000000000000" )
            )
            )
        )

        val issues = validator.validate( steps, provider )

        assertEquals( 1, issues.size )
        assertEquals( "PROTOCOL_NOT_FOUND", issues[0].code )
    }

    @Test
    fun `no provider warns that protocol bindings are unvalidated`()
    {
        val steps = listOf( step( "import", listOf( protocolInput( "hr", HEART_RATE ) ) ) )

        val issues = validator.validate( steps, provider = null )

        assertEquals( 1, issues.size )
        assertEquals( "PROTOCOL_NOT_VALIDATED", issues[0].code )
        assertEquals( PlanIssueSeverity.WARNING, issues[0].severity )
    }

    @Test
    fun `workflow with no protocol inputs yields no issues even without a provider`()
    {
        val steps = listOf( step( "import", listOf( externalInput( "open-data" ) ) ) )

        assertTrue( validator.validate( steps, provider = null ).isEmpty() )
        assertTrue(
            validator.validate(
                steps,
                StudyProtocolSnapshotDataTypeProvider( snapshot( dataTypes = listOf( HEART_RATE ) ) )
            ).isEmpty()
        )
    }

    @Test
    fun `provider selects requested protocol version`()
    {
        val provider = StudyProtocolSnapshotDataTypeProvider(
            snapshot( version = 1, dataTypes = listOf( STEP_COUNT ) ),
            snapshot( version = 2, dataTypes = listOf( HEART_RATE, STEP_COUNT ) )
        )

        // v1 does not collect heart rate -> ERROR
        val v1 = validator.validate(
            listOf( step( "import", listOf( protocolInput( "hr", HEART_RATE, version = 1 ) ) ) ),
            provider
        )
        assertEquals( 1, v1.size )
        assertEquals( "PROTOCOL_DATA_NOT_COLLECTED", v1[0].code )

        // v2 does -> clean
        val v2 = validator.validate(
            listOf( step( "import", listOf( protocolInput( "hr", HEART_RATE, version = 2 ) ) ) ),
            provider
        )
        assertTrue( v2.isEmpty() )
    }

    @Test
    fun `provider defaults to latest version when unspecified`()
    {
        val provider = StudyProtocolSnapshotDataTypeProvider(
            snapshot( version = 1, dataTypes = listOf( STEP_COUNT ) ),
            snapshot( version = 2, dataTypes = listOf( HEART_RATE, STEP_COUNT ) )
        )
        // No version on the input -> latest (v2) collects heart rate -> clean
        val issues = validator.validate(
            listOf( step( "import", listOf( protocolInput( "hr", HEART_RATE ) ) ) ),
            provider
        )
        assertTrue( issues.isEmpty() )
    }

    // ── Planner integration ───────────────────────────────────────────────────

    private class TestEnvironment(
        override val id: UUID = UUID.randomUUID(),
        override val name: String = "test-env",
        override val dependencies: List<String> = emptyList(),
        override val environmentVariables: Map<String, String> = emptyMap()
    ) : EnvironmentDefinition

    private fun definitionWith( step: Step, environment: EnvironmentDefinition ): WorkflowDefinition
    {
        val workflow = Workflow(
            WorkflowMetadata(
                id = UUID.randomUUID(),
                name = "Protocol coupling integration",
                description = null,
                version = Version( 1, 0 )
            )
        )
        workflow.addComponent( step )
        return WorkflowDefinition( workflow = workflow, environments = mapOf( environment.id to environment ) )
    }

    private fun protocolIssues( issues: List<dk.cachet.carp.analytics.application.plan.PlanIssue> ) =
        issues.filter { it.code.startsWith( "PROTOCOL_" ) }

    @Test
    fun `planner surfaces PROTOCOL_DATA_NOT_COLLECTED for mixed workflow against wrong protocol`()
    {
        val env = TestEnvironment()
        val definition = definitionWith(
            step(
                "import",
                listOf( protocolInput( "hr", HEART_RATE ), externalInput( "open-data" ) ),
                environmentId = env.id
            ),
            environment = env
        )
        val planner = DefaultExecutionPlanner(
            StudyProtocolSnapshotDataTypeProvider( snapshot( dataTypes = listOf( STEP_COUNT ) ) )
        )

        val plan = planner.plan( definition )
        val issues = protocolIssues( plan.issues )

        assertEquals( 1, issues.size )
        assertEquals( "PROTOCOL_DATA_NOT_COLLECTED", issues[0].code )
        assertEquals( PlanIssueSeverity.ERROR, issues[0].severity )
    }

    @Test
    fun `planner plans mixed workflow clean when protocol collects the bound data type`()
    {
        val env = TestEnvironment()
        val definition = definitionWith(
            step(
                "import",
                listOf( protocolInput( "hr", HEART_RATE ), externalInput( "open-data" ) ),
                environmentId = env.id
            ),
            environment = env
        )
        val planner = DefaultExecutionPlanner(
            StudyProtocolSnapshotDataTypeProvider( snapshot( dataTypes = listOf( HEART_RATE ) ) )
        )

        val plan = planner.plan( definition )

        assertTrue( protocolIssues( plan.issues ).isEmpty() )
        assertEquals( 1, plan.steps.size )
    }

    @Test
    fun `planner without provider warns protocol bindings are unvalidated`()
    {
        val env = TestEnvironment()
        val definition = definitionWith(
            step( "import", listOf( protocolInput( "hr", HEART_RATE ) ), environmentId = env.id ),
            environment = env
        )

        val plan = DefaultExecutionPlanner().plan( definition )
        val issues = protocolIssues( plan.issues )

        assertEquals( 1, issues.size )
        assertEquals( "PROTOCOL_NOT_VALIDATED", issues[0].code )
        assertEquals( PlanIssueSeverity.WARNING, issues[0].severity )
    }
}
