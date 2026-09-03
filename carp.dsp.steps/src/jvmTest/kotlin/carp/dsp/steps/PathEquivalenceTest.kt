package carp.dsp.steps

import carp.dsp.core.application.DeploymentTarget
import carp.dsp.core.application.StudyDataReader
import carp.dsp.core.application.StudyDataRequest
import carp.dsp.core.domain.data.Csv
import carp.dsp.steps.data.CarpStudyDataSource
import carp.dsp.steps.data.StudyDataStep
import carp.dsp.steps.data.StudyDataStepConfig
import carp.dsp.steps.datastream.CARP_JSON
import carp.dsp.steps.datastream.CarpDataStreamColumns
import carp.dsp.steps.datastream.CarpDataStreamSnapshot
import carp.dsp.steps.datastream.DecodeCarpDataStreamsStep
import dk.cachet.carp.common.application.UUID
import dk.cachet.carp.common.application.data.HeartRate
import dk.cachet.carp.common.application.data.StepCount
import dk.cachet.carp.data.application.DataStreamBatch
import dk.cachet.carp.data.application.DataStreamsConfiguration
import dk.cachet.carp.data.application.DataStreamsConfiguration.ExpectedDataStream
import dk.cachet.carp.data.application.MutableDataStreamBatch
import dk.cachet.carp.data.application.MutableDataStreamSequence
import dk.cachet.carp.data.application.SyncPoint
import dk.cachet.carp.data.infrastructure.InMemoryDataStreamService
import dk.cachet.carp.data.infrastructure.dataStreamId
import dk.cachet.carp.data.infrastructure.measurement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The two ways of reading a study have to produce the same tables.
 *
 * `core.io.fetch-study-data` asks the service; `core.io.query-sql` plus
 * `core.io.decode-carp-data-streams` read the rows the service wrote. A
 * workflow should not be able to tell which one ran, so the CSV is compared
 * byte for byte.
 *
 * Both sides start from the same batch, which is what makes the comparison
 * about the two paths rather than about the order a query returns rows in -
 * that belongs to the statement, and the documented one carries `ORDER BY`.
 */
class PathEquivalenceTest
{
    private val studyId = UUID.randomUUID()
    private val alice = UUID.randomUUID()
    private val syncPoint = SyncPoint(Instant.fromEpochMilliseconds(0L), 0L)

    private val steps = dataStreamId<StepCount>(alice, "phone")
    private val heartRate = dataStreamId<HeartRate>(alice, "watch")

    private suspend fun seededService(): InMemoryDataStreamService
    {
        val service = InMemoryDataStreamService()

        service.openDataStreams(
            DataStreamsConfiguration(
                alice,
                setOf(
                    ExpectedDataStream.fromDataStreamId(steps),
                    ExpectedDataStream.fromDataStreamId(heartRate),
                )
            )
        )

        val batch = MutableDataStreamBatch()
        batch.appendSequence(
            MutableDataStreamSequence<StepCount>(steps, 0L, listOf(1), syncPoint).apply {
                appendMeasurements(
                    measurement(StepCount(1000), 1_000_000L),
                    measurement(StepCount(2000), 2_000_000L),
                )
            }
        )
        batch.appendSequence(
            MutableDataStreamSequence<StepCount>(steps, 2L, listOf(1), syncPoint).apply {
                appendMeasurements(measurement(StepCount(3000), 3_000_000L))
            }
        )
        batch.appendSequence(
            MutableDataStreamSequence<HeartRate>(heartRate, 0L, listOf(1), syncPoint).apply {
                appendMeasurements(measurement(HeartRate(60), 1_500_000L))
            }
        )
        service.appendToDataStreams(alice, batch)

        return service
    }

    private class Writes
    {
        val files = mutableMapOf<String, String>()

        fun write(path: String, text: String)
        {
            files[path] = text
        }
    }

    /**
     * Returns [batch] as the table the documented query returns: one row per
     * sequence, with the snapshot as `data_stream_sequence.snapshot` holds it.
     *
     * The snapshot is written with the same record the decoder reads it with, so
     * this does not pin the wire format - the step's reference fixture does, from
     * JSON that was written by hand.
     */
    private fun storedRowsOf(batch: DataStreamBatch): String =
        Csv.table(
            CarpDataStreamColumns.ALL,
            batch.sequences.map { sequence ->
                listOf(
                    sequence.dataStream.studyDeploymentId.toString(),
                    sequence.dataStream.deviceRoleName,
                    sequence.dataStream.dataType.toString(),
                    sequence.firstSequenceId.toString(),
                    CARP_JSON.encodeToString(
                        CarpDataStreamSnapshot(sequence.measurements, sequence.triggerIds, sequence.syncPoint)
                    ),
                )
            }.toList(),
        )

    @Test
    fun `the service path and the SQL path write the same two tables`() = runTest {
        val service = seededService()
        val request = StudyDataRequest(studyId, listOf(DeploymentTarget(alice)), emptySet(), null, null)
        val reader = StudyDataReader(CarpStudyDataSource(service))

        val writes = Writes()
        StudyDataStep(reader = reader, readFile = { emptyList() }, writeFile = writes::write).run(
            StudyDataStepConfig(
                request = request,
                outputPath = "measurements.csv",
                provenancePath = "provenance.csv",
            )
        )

        val directory = Files.createTempDirectory("path-equivalence").toFile()
        val rows = File(directory, "rows.csv")
        rows.writeText(storedRowsOf(reader.read(request).originalBatch))

        val measurements = File(directory, "measurements.csv")
        val provenance = File(directory, "provenance.csv")
        DecodeCarpDataStreamsStep().run(rows, measurements, provenance)

        assertEquals(writes.files.getValue("measurements.csv"), measurements.readText())
        assertEquals(writes.files.getValue("provenance.csv"), provenance.readText())

        // A comparison of two empty tables would pass and mean nothing.
        assertTrue(measurements.readText().lineSequence().count() > 2, measurements.readText())
    }
}
