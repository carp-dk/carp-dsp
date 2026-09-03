package carp.dsp.core.domain.data

import dk.cachet.carp.common.application.UUID
import dk.cachet.carp.common.application.data.Acceleration
import dk.cachet.carp.common.application.data.DataType
import dk.cachet.carp.common.application.data.HeartRate
import dk.cachet.carp.common.application.data.StepCount
import dk.cachet.carp.data.application.DataStreamId
import dk.cachet.carp.data.application.MutableDataStreamBatch
import dk.cachet.carp.data.application.SyncPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class CarpTabularCsvTest {

    private val deploymentId = UUID.randomUUID()
    private val syncPoint = SyncPoint(Instant.fromEpochMilliseconds(1000L), 500L)

    private fun metadata(sequence: Int, measurement: Int, role: String, type: String) =
        CarpMeasurementMetadata(
            sequenceIndex = sequence,
            measurementIndex = measurement,
            dataStreamId = DataStreamId(deploymentId, role, DataType("dk.cachet.carp", type)),
            firstSequenceId = 100L,
            triggerIds = listOf(1, 2),
            syncPoint = syncPoint,
        )

    private fun steps(sequence: Int = 0, measurement: Int = 0, count: Int = 1500) =
        StepCountMeasurementRow(
            1000L, 2000L, DataType("dk.cachet.carp", "step_count"),
            metadata(sequence, measurement, "phone", "step_count"), StepCount(count)
        )

    private fun heartRate(sequence: Int = 1, measurement: Int = 0, bpm: Int = 75) =
        HeartRateMeasurementRow(
            1500L, null, DataType("dk.cachet.carp", "heart_rate"),
            metadata(sequence, measurement, "watch", "heart_rate"), HeartRate(bpm)
        )

    private fun acceleration(sequence: Int = 2, measurement: Int = 0) =
        AccelerationMeasurementRow(
            3000L, null, DataType("dk.cachet.carp", "acceleration"),
            metadata(sequence, measurement, "phone", "acceleration"), Acceleration(1.0, 2.0, 3.0)
        )

    private fun tabular(vararg rows: CarpMeasurementRow) =
        CarpTabularData(rows.toList(), MutableDataStreamBatch())

    private fun lines(csv: String) = csv.trimEnd('\n').split("\n")

    // ── Measurement table ─────────────────────────────────────────────────────

    @Test
    fun `columns are the union of the types present, and a type without one is blank`() {
        val csv = lines(CarpTabularCsv.measurements(tabular(steps(), heartRate())))

        assertEquals(
            "row_id,data_type,sensor_start_time,sensor_end_time,duration_ms,heart_rate.bpm,step_count.steps",
            csv[0]
        )
        // The step row carries no bpm, the heart rate row no steps.
        assertEquals("0:0,dk.cachet.carp.step_count,1000,2000,1000,,1500", csv[1])
        assertEquals("1:0,dk.cachet.carp.heart_rate,1500,,,75,", csv[2])
    }

    @Test
    fun `a type carrying several values gets a column each`() {
        val csv = lines(CarpTabularCsv.measurements(tabular(acceleration())))

        assertTrue(csv[0].endsWith("acceleration.x,acceleration.y,acceleration.z"), csv[0])
        assertTrue(csv[1].endsWith("1.0,2.0,3.0"), csv[1])
    }

    @Test
    fun `a requested column list is honoured, in order, including one nothing carries`() {
        val data = tabular(steps(), heartRate())

        val csv = lines(CarpTabularCsv.measurements(data, listOf("heart_rate.bpm", "not_collected.value")))

        assertTrue(csv[0].endsWith("heart_rate.bpm,not_collected.value"), csv[0])
        // Asking for a column is a claim about the output's shape, so it is
        // written empty rather than dropped.
        assertEquals("0:0,dk.cachet.carp.step_count,1000,2000,1000,,", csv[1])
        assertEquals("1:0,dk.cachet.carp.heart_rate,1500,,,75,", csv[2])
    }

    @Test
    fun `the header is stable whatever order the rows arrive in`() {
        val forwards = CarpTabularCsv.measurements(tabular(steps(), heartRate(), acceleration()))
        val backwards = CarpTabularCsv.measurements(tabular(acceleration(), heartRate(), steps()))

        assertEquals(lines(forwards)[0], lines(backwards)[0])
    }

    @Test
    fun `an empty table is a header and nothing else`() {
        val csv = lines(CarpTabularCsv.measurements(tabular()))

        assertEquals(1, csv.size)
        assertEquals("row_id,data_type,sensor_start_time,sensor_end_time,duration_ms", csv[0])
    }

    // ── Provenance table ──────────────────────────────────────────────────────

    @Test
    fun `provenance is keyed by row_id and carries the stream and sync point`() {
        val csv = lines(CarpTabularCsv.provenance(tabular(steps(sequence = 3, measurement = 7))))

        assertEquals(
            "row_id,study_deployment_id,device_role_name,data_type,sequence_index," +
                "measurement_index,first_sequence_id,trigger_ids," +
                "sync_synchronized_on_ms,sync_sensor_timestamp,sync_relative_clock_speed",
            csv[0]
        )
        assertEquals(
            "3:7,$deploymentId,phone,dk.cachet.carp.step_count,3,7,100,1 2,1000,500,1.0",
            csv[1]
        )
    }

    @Test
    fun `every measurement row has exactly one provenance row, joinable on row_id`() {
        val data = tabular(steps(), heartRate(), acceleration())

        val measured = lines(CarpTabularCsv.measurements(data)).drop(1).map { it.substringBefore(",") }
        val provenance = lines(CarpTabularCsv.provenance(data)).drop(1).map { it.substringBefore(",") }

        assertEquals(measured, provenance)
        assertEquals(measured.toSet().size, measured.size)
    }

    // ── Escaping ──────────────────────────────────────────────────────────────

    @Test
    fun `a cell containing a comma or a quote is quoted`() {
        val awkward = StepCountMeasurementRow(
            1000L, null, DataType("dk.cachet.carp", "step_count"),
            metadata(0, 0, "phone, \"left\" pocket", "step_count"), StepCount(1)
        )

        val csv = lines(CarpTabularCsv.provenance(tabular(awkward)))

        assertTrue(csv[1].contains("\"phone, \"\"left\"\" pocket\""), csv[1])
    }
}
