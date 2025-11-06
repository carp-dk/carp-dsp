package carp.dsp.core.domain.data

import carp.dsp.core.domain.data.CarpMeasurementMetadata
import dk.cachet.carp.common.application.UUID
import dk.cachet.carp.common.application.data.DataType
import dk.cachet.carp.data.application.DataStreamId
import dk.cachet.carp.data.application.SyncPoint
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class CarpMeasurementMetadataTest {

    private val testStudyDeploymentId = UUID.randomUUID()
    private val testDeviceRoleName = "phone"
    private val testDataType = DataType("dk.cachet.carp", "step_count")
    private val testDataStreamId = DataStreamId(testStudyDeploymentId, testDeviceRoleName, testDataType)
    private val testSyncPoint = SyncPoint(Instant.fromEpochMilliseconds(1234567890L), 1L)

    private val metadata = CarpMeasurementMetadata(
        sequenceIndex = 0,
        measurementIndex = 42,
        dataStreamId = testDataStreamId,
        firstSequenceId = 100L,
        triggerIds = listOf(1, 2, 3),
        syncPoint = testSyncPoint
    )

    @Test
    fun testBasicProperties() {
        assertEquals(0, metadata.sequenceIndex)
        assertEquals(42, metadata.measurementIndex)
        assertEquals(testDataStreamId, metadata.dataStreamId)
        assertEquals(100L, metadata.firstSequenceId)
        assertEquals(listOf(1, 2, 3), metadata.triggerIds)
        assertEquals(testSyncPoint, metadata.syncPoint)
    }

    @Test
    fun testConvenienceProperties() {
        assertEquals(testStudyDeploymentId, metadata.studyDeploymentId)
        assertEquals(testDeviceRoleName, metadata.deviceRoleName)
        assertEquals(testDataType, metadata.dataType)
        assertEquals(testDataType.toString(), metadata.dataTypeString)
    }

    @Test
    fun testDataTypeStringRepresentation() {
        // Test that dataTypeString returns the string representation of the DataType
        val expectedString = testDataType.toString()
        assertEquals(expectedString, metadata.dataTypeString)
    }
}
