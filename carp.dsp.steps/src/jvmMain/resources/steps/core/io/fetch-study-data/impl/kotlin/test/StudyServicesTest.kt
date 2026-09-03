package carp.dsp.steps.data

import carp.dsp.core.application.DeploymentTarget
import carp.dsp.core.application.StudyDataRequest
import dk.cachet.carp.common.application.UUID
import dk.cachet.carp.common.application.data.StepCount
import dk.cachet.carp.data.application.DataStreamsConfiguration
import dk.cachet.carp.data.application.DataStreamsConfiguration.ExpectedDataStream
import dk.cachet.carp.data.application.MutableDataStreamBatch
import dk.cachet.carp.data.application.MutableDataStreamSequence
import dk.cachet.carp.data.application.SyncPoint
import dk.cachet.carp.data.infrastructure.dataStreamId
import dk.cachet.carp.data.infrastructure.measurement
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.time.Instant

class StudyServicesTest {

    @Test
    fun `the in-memory name creates a fresh stack each time`() {
        val a = StudyServicesFactory.create(StudyServicesFactory.IN_MEMORY)
        val b = StudyServicesFactory.create(StudyServicesFactory.IN_MEMORY)

        assertTrue(a is InMemoryStudyServices)
        // Two runs must not share a study or its data.
        assertNotSame(a.dataStreams, b.dataStreams)
    }

    @Test
    fun `an unknown name is refused and the known names are listed`() {
        val failure = assertFailsWith<IllegalArgumentException> { StudyServicesFactory.create("postgres") }

        assertTrue(failure.message!!.contains("postgres"), failure.message!!)
        assertTrue(failure.message!!.contains(StudyServicesFactory.IN_MEMORY), failure.message!!)
    }

    @Test
    fun `a step built over services reads what those services hold`() = runTest {
        val services = InMemoryStudyServices()
        val deployment = UUID.randomUUID()
        val stream = dataStreamId<StepCount>(deployment, "phone")
        services.dataStreams.openDataStreams(
            DataStreamsConfiguration(deployment, setOf(ExpectedDataStream.fromDataStreamId(stream)))
        )
        services.dataStreams.appendToDataStreams(
            deployment,
            MutableDataStreamBatch().apply {
                appendSequence(
                    MutableDataStreamSequence<StepCount>(
                        stream, 0L, listOf(1), SyncPoint(Instant.fromEpochMilliseconds(0L), 0L)
                    ).apply { appendMeasurements(measurement(StepCount(42), 1_000_000L)) }
                )
            },
        )
        val writes = mutableMapOf<String, String>()
        val step = StudyDataStep.over(services, readFile = { emptyList() }, writeFile = { p, t -> writes[p] = t })

        step.run(
            StudyDataStepConfig(
                request = StudyDataRequest(UUID.randomUUID(), listOf(DeploymentTarget(deployment))),
                outputPath = "out.csv",
            )
        )

        assertEquals(1, writes.getValue("out.csv").trimEnd('\n').split("\n").drop(1).size)
        assertTrue(writes.getValue("out.csv").contains(",42"), writes.getValue("out.csv"))
    }
}
