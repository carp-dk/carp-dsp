package carp.dsp.core.domain.data

import dk.cachet.carp.common.application.UUID
import dk.cachet.carp.common.application.data.Acceleration
import dk.cachet.carp.common.application.data.DataType
import dk.cachet.carp.common.application.data.Geolocation
import dk.cachet.carp.common.application.data.HeartRate
import dk.cachet.carp.common.application.data.StepCount
import dk.cachet.carp.data.application.DataStreamId
import dk.cachet.carp.data.application.SyncPoint
import kotlinx.datetime.Instant
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CarpMeasurementRowTest {

    private fun createTestMetadata(sequenceIndex: Int = 0): CarpMeasurementMetadata {
        val studyDeploymentId = UUID.randomUUID()
        val deviceRoleName = "testDevice"
        val dataType = DataType("dk.cachet.carp", "step_count")
        val dataStreamId = DataStreamId(studyDeploymentId, deviceRoleName, dataType)
        val syncPoint = SyncPoint(Instant.fromEpochMilliseconds(1234567890L), 1L)

        return CarpMeasurementMetadata(
            sequenceIndex = sequenceIndex,
            measurementIndex = 0,
            dataStreamId = dataStreamId,
            firstSequenceId = 100L,
            triggerIds = listOf(1),
            syncPoint = syncPoint
        )
    }

    @Test
    fun testStepCountMeasurementRow() {
        val stepCountData = StepCount(1500)
        val dataType = DataType("dk.cachet.carp", "step_count")
        val metadata = createTestMetadata()

        val row = StepCountMeasurementRow(
            sensorStartTime = 1000L,
            sensorEndTime = 2000L,
            dataType = dataType,
            metadata = metadata,
            stepCount = stepCountData
        )

        assertEquals(1000L, row.sensorStartTime)
        assertEquals(2000L, row.sensorEndTime)
        assertEquals(1000L, row.sensorTimestamp) // Backward compatibility
        assertEquals(1000L, row.duration) // 2000 - 1000
        assertEquals(dataType, row.dataType)
        assertEquals(stepCountData, row.originalData)
        assertEquals(1500, row.steps)
    }

    @Test
    fun testStepCountMeasurementRowInstantaneous() {
        val stepCountData = StepCount(1500)
        val dataType = DataType("dk.cachet.carp", "step_count")
        val metadata = createTestMetadata()

        val row = StepCountMeasurementRow(
            sensorStartTime = 1000L,
            sensorEndTime = null, // Instantaneous measurement
            dataType = dataType,
            metadata = metadata,
            stepCount = stepCountData
        )

        assertEquals(1000L, row.sensorStartTime)
        assertNull(row.sensorEndTime)
        assertNull(row.duration)
        assertEquals(1500, row.steps)
    }

    @Test
    fun testAccelerationMeasurementRow() {
        val accelerationData = Acceleration(1.5, -2.0, 9.8)
        val dataType = DataType("dk.cachet.carp", "acceleration")
        val metadata = createTestMetadata()

        val row = AccelerationMeasurementRow(
            sensorStartTime = 1000L,
            sensorEndTime = null,
            dataType = dataType,
            metadata = metadata,
            acceleration = accelerationData
        )

        assertEquals(1.5, row.x)
        assertEquals(-2.0, row.y)
        assertEquals(9.8, row.z)
        assertEquals(accelerationData, row.originalData)

        // Test magnitude calculation: sqrt(1.5² + (-2.0)² + 9.8²)
        val expectedMagnitude = sqrt(1.5 * 1.5 + (-2.0) * (-2.0) + 9.8 * 9.8)
        assertEquals(expectedMagnitude, row.magnitude, 0.001)
    }

    @Test
    fun testHeartRateMeasurementRow() {
        val heartRateData = HeartRate(75)
        val dataType = DataType("dk.cachet.carp", "heart_rate")
        val metadata = createTestMetadata()

        val row = HeartRateMeasurementRow(
            sensorStartTime = 1000L,
            sensorEndTime = 5000L,
            dataType = dataType,
            metadata = metadata,
            heartRate = heartRateData
        )

        assertEquals(75, row.bpm)
        assertEquals(heartRateData, row.originalData)
        assertEquals(4000L, row.duration) // 5000 - 1000
    }

    @Test
    fun testGenericMeasurementRow() {
        val customData = StepCount(100) // Using StepCount as a generic data example
        val dataType = DataType("dk.cachet.carp", "custom_data")
        val metadata = createTestMetadata()

        val row = GenericMeasurementRow(
            sensorStartTime = 1000L,
            sensorEndTime = null,
            dataType = dataType,
            metadata = metadata,
            originalData = customData
        )

        assertEquals(1000L, row.sensorStartTime)
        assertNull(row.sensorEndTime)
        assertEquals(dataType, row.dataType)
        assertEquals(customData, row.originalData)
        assertNull(row.duration)
    }

    @Test
    fun testSensorTimestampBackwardCompatibility() {
        val stepCountData = StepCount(1000)
        val dataType = DataType("dk.cachet.carp", "step_count")
        val metadata = createTestMetadata()

        val row = StepCountMeasurementRow(
            sensorStartTime = 12345L,
            sensorEndTime = null,
            dataType = dataType,
            metadata = metadata,
            stepCount = stepCountData
        )

        // sensorTimestamp should point to sensorStartTime for backward compatibility
        assertEquals(row.sensorStartTime, row.sensorTimestamp)
        assertEquals(12345L, row.sensorTimestamp)
    }

    @Test
    fun testGeolocationMeasurementRow() {
    val latitude = 37.7749
    val longitude = -122.4194
    val geolocationData = Geolocation(latitude, longitude)
    val dataType = DataType("dk.cachet.carp", "geolocation")
    val metadata = createTestMetadata()

    val row = GeolocationMeasurementRow(
        sensorStartTime = 1000L,
        sensorEndTime = null,
        dataType = dataType,
        metadata = metadata,
        geolocation = geolocationData
    )

    assertEquals(latitude, row.latitude)
    assertEquals(longitude, row.longitude)
}
}
