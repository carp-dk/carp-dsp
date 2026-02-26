package carp.dsp.core.application

import carp.dsp.core.domain.data.*
import dk.cachet.carp.common.application.UUID
import dk.cachet.carp.common.application.data.*
import dk.cachet.carp.common.infrastructure.test.StubDataPoint
import dk.cachet.carp.data.application.*
import dk.cachet.carp.data.infrastructure.dataStreamId
import dk.cachet.carp.data.infrastructure.measurement
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataStreamBatchConverterTest {

    private val converter = DataStreamBatchConverter()
    private val studyDeploymentId = UUID.randomUUID()
    private val syncPoint = SyncPoint(Instant.fromEpochMilliseconds(1000L), 1000000L)

    @Test
    fun testCreateTypedMeasurementRowStepCount() {
        val emptyBatch = MutableDataStreamBatch()
        val tabularData = converter.toTabularData(emptyBatch)

        assertEquals(0, tabularData.size)
        assertEquals(emptyBatch, tabularData.originalBatch)
    }

    @Test
    fun testEmptyBatchConversion() {
        val emptyBatch = MutableDataStreamBatch()
        val tabularData = converter.toTabularData(emptyBatch)

        assertEquals(0, tabularData.size)
        assertTrue(tabularData.isEmpty)
        assertTrue(tabularData.deviceRoleNames.isEmpty())
        assertTrue(tabularData.dataTypes.isEmpty())
        assertEquals(emptyBatch, tabularData.originalBatch)
    }

    @Test
    fun testTypeSpecificConversionEmpty() {
        val emptyBatch = MutableDataStreamBatch()

        val stepCountOnly = converter.toTypedTabularData<StepCount>(emptyBatch)
        assertEquals(0, stepCountOnly.size)

        val heartRateOnly = converter.toTypedTabularData<HeartRate>(emptyBatch)
        assertEquals(0, heartRateOnly.size)
    }

    @Test
    fun testFilteredConversionMethodsEmpty() {
        val emptyBatch = MutableDataStreamBatch()
        val studyDeploymentId = UUID.randomUUID()

        val phoneData = converter.toTabularDataForDeviceRole(emptyBatch, "phone")
        assertEquals(0, phoneData.size)

        val deploymentData = converter.toTabularDataForStudyDeployment(emptyBatch, studyDeploymentId)
        assertEquals(0, deploymentData.size)

        val timeRangeData = converter.toTabularDataForTimeRange(emptyBatch, 1000L, 2000L)
        assertEquals(0, timeRangeData.size)
    }

    @Test
    fun `createTypedMeasurementRow creates StepCountMeasurementRow for StepCount data`() {
        val batch = createBatchWithStepCountData()
        val tabularData = converter.toTabularData(batch)

        assertEquals(1, tabularData.size)
        val row = tabularData.rows.first()
        assertTrue(row is StepCountMeasurementRow)

        assertEquals(1500, row.steps)
        assertEquals(1000L, row.sensorStartTime)
        assertEquals(2000L, row.sensorEndTime)
        assertTrue(row.originalData is StepCount)
    }

    @Test
    fun `createTypedMeasurementRow creates AccelerationMeasurementRow for Acceleration data`() {
        val batch = createBatchWithAccelerationData()
        val tabularData = converter.toTabularData(batch)

        assertEquals(1, tabularData.size)
        val row = tabularData.rows.first()
        assertTrue(row is AccelerationMeasurementRow)

        assertEquals(1.5, row.x)
        assertEquals(2.5, row.y)
        assertEquals(3.5, row.z)
        assertEquals(1500L, row.sensorStartTime)
        assertTrue(row.originalData is Acceleration)
    }

    @Test
    fun `createTypedMeasurementRow creates GeolocationMeasurementRow for Geolocation data`() {
        val batch = createBatchWithGeolocationData()
        val tabularData = converter.toTabularData(batch)

        assertEquals(1, tabularData.size)
        val row = tabularData.rows.first()
        assertTrue(row is GeolocationMeasurementRow)

        assertEquals(55.68, row.latitude)
        assertEquals(12.58, row.longitude)
        assertEquals(2000L, row.sensorStartTime)
        assertTrue(row.originalData is Geolocation)
    }

    @Test
    fun `createTypedMeasurementRow creates HeartRateMeasurementRow for HeartRate data`() {
        val batch = createBatchWithHeartRateData()
        val tabularData = converter.toTabularData(batch)

        assertEquals(1, tabularData.size)
        val row = tabularData.rows.first()
        assertTrue(row is HeartRateMeasurementRow)

        assertEquals(75, row.bpm)
        assertEquals(2500L, row.sensorStartTime)
        assertEquals(3000L, row.sensorEndTime)
        assertTrue(row.originalData is HeartRate)
    }

    @Test
    fun `createTypedMeasurementRow creates GenericMeasurementRow for unknown data types`() {
        val batch = createBatchWithGenericData()
        val tabularData = converter.toTabularData(batch)

        assertEquals(1, tabularData.size)
        val row = tabularData.rows.first()
        assertTrue(row is GenericMeasurementRow)

        assertEquals(3000L, row.sensorStartTime)
        assertEquals(null, row.sensorEndTime)
        assertTrue(row.originalData is StubDataPoint)
    }

    @Test
    fun `createTypedMeasurementRow handles null sensorEndTime correctly`() {
        val batch = createBatchWithNullEndTime()
        val tabularData = converter.toTabularData(batch)

        assertEquals(1, tabularData.size)
        val row = tabularData.rows.first()
        assertEquals(4000L, row.sensorStartTime)
        assertEquals(null, row.sensorEndTime)
        assertEquals(null, row.duration)
    }

    @Test
    fun `createTypedMeasurementRow preserves all metadata fields`() {
        val batch = createBatchWithMetadata()
        val tabularData = converter.toTabularData(batch)

        assertEquals(1, tabularData.size)
        val row = tabularData.rows.first()
        val metadata = row.metadata

        assertEquals(0, metadata.sequenceIndex)
        assertEquals(0, metadata.measurementIndex)
        assertEquals("phone", metadata.deviceRoleName)
        assertEquals(studyDeploymentId, metadata.studyDeploymentId)
        assertEquals(100L, metadata.firstSequenceId)
        assertEquals(listOf(1, 2), metadata.triggerIds)
        assertEquals(syncPoint, metadata.syncPoint)
    }

    @Test
    fun `toTabularData handles multiple data types in single batch`() {
        val batch = createMixedDataBatch()
        val tabularData = converter.toTabularData(batch)

        assertEquals(4, tabularData.size)

        val stepCountRows = tabularData.rows.filterIsInstance<StepCountMeasurementRow>()
        val accelerationRows = tabularData.rows.filterIsInstance<AccelerationMeasurementRow>()
        val heartRateRows = tabularData.rows.filterIsInstance<HeartRateMeasurementRow>()
        val genericRows = tabularData.rows.filterIsInstance<GenericMeasurementRow>()

        assertEquals(1, stepCountRows.size)
        assertEquals(1, accelerationRows.size)
        assertEquals(1, heartRateRows.size)
        assertEquals(1, genericRows.size)
    }

    @Test
    fun `toTabularData handles multiple sequences with different measurement counts`() {
        val batch = createMultiSequenceBatch()
        val tabularData = converter.toTabularData(batch)

        assertEquals(3, tabularData.size)

        // Verify sequence indices are correct
        val sequenceIndices = tabularData.rows.map { it.metadata.sequenceIndex }.distinct().sorted()
        assertEquals(listOf(0, 1), sequenceIndices)

        // First sequence should have 2 measurements
        val firstSequenceMeasurements = tabularData.rows.filter { it.metadata.sequenceIndex == 0 }
        assertEquals(2, firstSequenceMeasurements.size)

        // Second sequence should have 1 measurement
        val secondSequenceMeasurements = tabularData.rows.filter { it.metadata.sequenceIndex == 1 }
        assertEquals(1, secondSequenceMeasurements.size)
    }

    @Test
    fun `toTypedTabularData filters data by specific type`() {
        val batch = createMixedDataBatch()

        val stepCountData = converter.toTypedTabularData<StepCount>(batch)
        assertEquals(1, stepCountData.size)
        assertTrue(stepCountData.rows.all { it is StepCountMeasurementRow })

        val heartRateData = converter.toTypedTabularData<HeartRate>(batch)
        assertEquals(1, heartRateData.size)
        assertTrue(heartRateData.rows.all { it is HeartRateMeasurementRow })

        val accelerationData = converter.toTypedTabularData<Acceleration>(batch)
        assertEquals(1, accelerationData.size)
        assertTrue(accelerationData.rows.all { it is AccelerationMeasurementRow })

        val stubData = converter.toTypedTabularData<StubDataPoint>(batch)
        assertEquals(1, stubData.size)
        assertTrue(stubData.rows.all { it is GenericMeasurementRow })
    }

    @Test
    fun `toTabularDataForDeviceRole filters by device role`() {
        val batch = createMixedDataBatch()

        val phoneData = converter.toTabularDataForDeviceRole(batch, "phone")
        assertEquals(2, phoneData.size) // StepCount and Acceleration are from phone

        val watchData = converter.toTabularDataForDeviceRole(batch, "watch")
        assertEquals(1, watchData.size) // HeartRate is from watch

        val sensorData = converter.toTabularDataForDeviceRole(batch, "sensor")
        assertEquals(1, sensorData.size) // StubDataPoint is from sensor

        val nonExistentData = converter.toTabularDataForDeviceRole(batch, "tablet")
        assertEquals(0, nonExistentData.size)
    }

    @Test
    fun `toTabularDataForStudyDeployment filters by deployment ID`() {
        val batch = createMixedDataBatch()
        val differentDeploymentId = UUID.randomUUID()

        val correctDeploymentData = converter.toTabularDataForStudyDeployment(batch, studyDeploymentId)
        assertEquals(4, correctDeploymentData.size)

        val wrongDeploymentData = converter.toTabularDataForStudyDeployment(batch, differentDeploymentId)
        assertEquals(0, wrongDeploymentData.size)
    }

    @Test
    fun `toTabularDataForTimeRange filters by time range`() {
        val batch = createMixedDataBatch()

        // Time range that includes first two measurements (1000L and 2000L)
        val earlyRange = converter.toTabularDataForTimeRange(batch, 500L, 2500L)
        assertEquals(2, earlyRange.size)

        // Time range that includes last two measurements (3000L and 4000L)
        val lateRange = converter.toTabularDataForTimeRange(batch, 2500L, 4500L)
        assertEquals(2, lateRange.size)

        // Time range that includes all measurements
        val fullRange = converter.toTabularDataForTimeRange(batch, 0L, 5000L)
        assertEquals(4, fullRange.size)

        // Time range that includes no measurements
        val emptyRange = converter.toTabularDataForTimeRange(batch, 5000L, 6000L)
        assertEquals(0, emptyRange.size)
    }

    @Test
    fun `createTypedMeasurementRow handles edge case data with special values`() {
        val batch = createBatchWithEdgeCaseData()
        val tabularData = converter.toTabularData(batch)

        assertEquals(3, tabularData.size)

        // Test zero-step count
        val zeroStepRow = tabularData.rows[0] as StepCountMeasurementRow
        assertEquals(0, zeroStepRow.steps)

        // Test negative acceleration values
        val negAccelRow = tabularData.rows[1] as AccelerationMeasurementRow
        assertEquals(-1.0, negAccelRow.x)
        assertEquals(-2.0, negAccelRow.y)
        assertEquals(-3.0, negAccelRow.z)

        // Test high heart rate
        val highHrRow = tabularData.rows[2] as HeartRateMeasurementRow
        assertEquals(200, highHrRow.bpm)
    }

    @Test
    fun `toTabularData calculates measurement indices correctly for multiple measurements`() {
        val batch = createMultiSequenceBatch()
        val tabularData = converter.toTabularData(batch)

        // Check measurement indices in first sequence
        val firstSequenceRows = tabularData.rows.filter { it.metadata.sequenceIndex == 0 }
        assertEquals(0, firstSequenceRows[0].metadata.measurementIndex)
        assertEquals(1, firstSequenceRows[1].metadata.measurementIndex)

        // Check measurement index in second sequence
        val secondSequenceRows = tabularData.rows.filter { it.metadata.sequenceIndex == 1 }
        assertEquals(0, secondSequenceRows[0].metadata.measurementIndex)
    }

    // Helper methods to create test data

    private fun createBatchWithStepCountData(): MutableDataStreamBatch {
        val batch = MutableDataStreamBatch()
        val dataStream = dataStreamId<StepCount>(studyDeploymentId, "phone")
        val sequence = MutableDataStreamSequence<StepCount>(dataStream, 100L, listOf(1), syncPoint)
        sequence.appendMeasurements(measurement(StepCount(1500), 1000L, 2000L))
        batch.appendSequence(sequence)
        return batch
    }

    private fun createBatchWithAccelerationData(): MutableDataStreamBatch {
        val batch = MutableDataStreamBatch()
        val dataStream = dataStreamId<Acceleration>(studyDeploymentId, "phone")
        val sequence = MutableDataStreamSequence<Acceleration>(dataStream, 200L, listOf(2), syncPoint)
        sequence.appendMeasurements(measurement(Acceleration(1.5, 2.5, 3.5), 1500L))
        batch.appendSequence(sequence)
        return batch
    }

    private fun createBatchWithGeolocationData(): MutableDataStreamBatch {
        val batch = MutableDataStreamBatch()
        val dataStream = dataStreamId<Geolocation>(studyDeploymentId, "phone")
        val sequence = MutableDataStreamSequence<Geolocation>(dataStream, 300L, listOf(3), syncPoint)
        sequence.appendMeasurements(measurement(Geolocation(55.68, 12.58), 2000L))
        batch.appendSequence(sequence)
        return batch
    }

    private fun createBatchWithHeartRateData(): MutableDataStreamBatch {
        val batch = MutableDataStreamBatch()
        val dataStream = dataStreamId<HeartRate>(studyDeploymentId, "watch")
        val sequence = MutableDataStreamSequence<HeartRate>(dataStream, 400L, listOf(4), syncPoint)
        sequence.appendMeasurements(measurement(HeartRate(75), 2500L, 3000L))
        batch.appendSequence(sequence)
        return batch
    }

    private fun createBatchWithGenericData(): MutableDataStreamBatch {
        val batch = MutableDataStreamBatch()
        val dataStream = dataStreamId<StubDataPoint>(studyDeploymentId, "sensor")
        val sequence = MutableDataStreamSequence<StubDataPoint>(dataStream, 500L, listOf(5), syncPoint)
        sequence.appendMeasurements(measurement(StubDataPoint("test"), 3000L))
        batch.appendSequence(sequence)
        return batch
    }

    private fun createBatchWithNullEndTime(): MutableDataStreamBatch {
        val batch = MutableDataStreamBatch()
        val dataStream = dataStreamId<StepCount>(studyDeploymentId, "phone")
        val sequence = MutableDataStreamSequence<StepCount>(dataStream, 600L, listOf(6), syncPoint)
        sequence.appendMeasurements(measurement(StepCount(2000), 4000L, null))
        batch.appendSequence(sequence)
        return batch
    }

    private fun createBatchWithMetadata(): MutableDataStreamBatch {
        val batch = MutableDataStreamBatch()
        val dataStream = dataStreamId<StepCount>(studyDeploymentId, "phone")
        val sequence = MutableDataStreamSequence<StepCount>(dataStream, 100L, listOf(1, 2), syncPoint)
        sequence.appendMeasurements(measurement(StepCount(1000), 5000L))
        batch.appendSequence(sequence)
        return batch
    }

    private fun createMixedDataBatch(): MutableDataStreamBatch {
        val batch = MutableDataStreamBatch()

        // Add StepCount
        val stepDataStream = dataStreamId<StepCount>(studyDeploymentId, "phone")
        val stepSequence = MutableDataStreamSequence<StepCount>(stepDataStream, 100L, listOf(1), syncPoint)
        stepSequence.appendMeasurements(measurement(StepCount(1000), 1000L))
        batch.appendSequence(stepSequence)

        // Add Acceleration
        val accelDataStream = dataStreamId<Acceleration>(studyDeploymentId, "phone")
        val accelSequence = MutableDataStreamSequence<Acceleration>(accelDataStream, 200L, listOf(2), syncPoint)
        accelSequence.appendMeasurements(measurement(Acceleration(1.0, 2.0, 3.0), 2000L))
        batch.appendSequence(accelSequence)

        // Add HeartRate
        val hrDataStream = dataStreamId<HeartRate>(studyDeploymentId, "watch")
        val hrSequence = MutableDataStreamSequence<HeartRate>(hrDataStream, 300L, listOf(3), syncPoint)
        hrSequence.appendMeasurements(measurement(HeartRate(80), 3000L))
        batch.appendSequence(hrSequence)

        // Add Generic data
        val genericDataStream = dataStreamId<StubDataPoint>(studyDeploymentId, "sensor")
        val genericSequence = MutableDataStreamSequence<StubDataPoint>(genericDataStream, 400L, listOf(4), syncPoint)
        genericSequence.appendMeasurements(measurement(StubDataPoint("generic"), 4000L))
        batch.appendSequence(genericSequence)

        return batch
    }

    private fun createMultiSequenceBatch(): MutableDataStreamBatch {
        val batch = MutableDataStreamBatch()

        // First sequence with 2 measurements
        val dataStream1 = dataStreamId<StepCount>(studyDeploymentId, "phone")
        val sequence1 = MutableDataStreamSequence<StepCount>(dataStream1, 100L, listOf(1), syncPoint)
        sequence1.appendMeasurements(
            measurement(StepCount(1000), 1000L),
            measurement(StepCount(1100), 2000L)
        )
        batch.appendSequence(sequence1)

        // Second sequence with 1 measurement
        val dataStream2 = dataStreamId<HeartRate>(studyDeploymentId, "watch")
        val sequence2 = MutableDataStreamSequence<HeartRate>(dataStream2, 200L, listOf(2), syncPoint)
        sequence2.appendMeasurements(measurement(HeartRate(75), 3000L))
        batch.appendSequence(sequence2)

        return batch
    }

    private fun createBatchWithEdgeCaseData(): MutableDataStreamBatch {
        val batch = MutableDataStreamBatch()

        // Zero-step count
        val stepDataStream = dataStreamId<StepCount>(studyDeploymentId, "phone")
        val stepSequence = MutableDataStreamSequence<StepCount>(stepDataStream, 100L, listOf(1), syncPoint)
        stepSequence.appendMeasurements(measurement(StepCount(0), 1000L))
        batch.appendSequence(stepSequence)

        // Negative acceleration values
        val accelDataStream = dataStreamId<Acceleration>(studyDeploymentId, "phone")
        val accelSequence = MutableDataStreamSequence<Acceleration>(accelDataStream, 200L, listOf(2), syncPoint)
        accelSequence.appendMeasurements(measurement(Acceleration(-1.0, -2.0, -3.0), 2000L))
        batch.appendSequence(accelSequence)

        // High heart rate
        val hrDataStream = dataStreamId<HeartRate>(studyDeploymentId, "watch")
        val hrSequence = MutableDataStreamSequence<HeartRate>(hrDataStream, 300L, listOf(3), syncPoint)
        hrSequence.appendMeasurements(measurement(HeartRate(200), 3000L))
        batch.appendSequence(hrSequence)

        return batch
    }
}
