package carp.dsp.steps.data

import carp.dsp.core.application.DeploymentTarget
import carp.dsp.core.application.StudyDataReader
import carp.dsp.core.application.StudyDataRequest
import dk.cachet.carp.common.application.UUID
import dk.cachet.carp.common.application.data.StepCount
import dk.cachet.carp.data.application.DataStreamsConfiguration
import dk.cachet.carp.data.application.DataStreamsConfiguration.ExpectedDataStream
import dk.cachet.carp.data.application.MutableDataStreamBatch
import dk.cachet.carp.data.application.MutableDataStreamSequence
import dk.cachet.carp.data.application.SyncPoint
import dk.cachet.carp.data.infrastructure.InMemoryDataStreamService
import dk.cachet.carp.data.infrastructure.dataStreamId
import dk.cachet.carp.data.infrastructure.measurement
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The step reproduces the fixture it publishes.
 *
 * A step that reads a service has no file input to fix, so the published
 * `reference/expected.csv` is pinned here instead: this test seeds the
 * deployment the fixture describes and asserts the step writes the published
 * bytes.
 */
class FetchStudyDataFixtureTest {

    private val deployment = UUID.parse("4f2b8a10-0000-4000-8000-000000000001")
    private val role = "phone"

    /**
     * Line endings are normalised because git may check the fixture out with
     * CRLF; what the fixture pins is the table, not the checkout.
     */
    private fun published(): String = checkNotNull(
        javaClass.classLoader.getResource("steps/core/io/fetch-study-data/reference/expected.csv")
    ) { "the step's reference fixture is not on the classpath" }
        .readText()
        .replace("\r\n", "\n")

    private suspend fun seeded(): InMemoryDataStreamService {
        val service = InMemoryDataStreamService()
        val stream = dataStreamId<StepCount>(deployment, role)

        service.openDataStreams(
            DataStreamsConfiguration(deployment, setOf(ExpectedDataStream.fromDataStreamId(stream)))
        )

        val batch = MutableDataStreamBatch()
        batch.appendSequence(
            MutableDataStreamSequence<StepCount>(
                stream,
                0L,
                listOf(1),
                SyncPoint(Instant.fromEpochMilliseconds(0L), 0L),
            ).apply {
                appendMeasurements(
                    measurement(StepCount(1000), 1_000_000L),
                    measurement(StepCount(2000), 2_000_000L),
                )
            }
        )
        service.appendToDataStreams(deployment, batch)

        return service
    }

    @Test
    fun `the step writes the fixture it publishes`() = runTest {
        val written = mutableMapOf<String, String>()
        val step = StudyDataStep(
            reader = StudyDataReader(CarpStudyDataSource(seeded())),
            readFile = { emptyList() },
            writeFile = { path, text -> written[path] = text },
        )

        step.run(
            StudyDataStepConfig(
                request = StudyDataRequest(
                    studyId = UUID.randomUUID(),
                    targets = listOf(DeploymentTarget(deployment, role)),
                ),
                outputPath = "measurements.csv",
            )
        )

        assertEquals(published(), written.getValue("measurements.csv"))
    }
}
