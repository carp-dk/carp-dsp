package carp.dsp.core.application

import carp.dsp.core.domain.data.CarpMeasurementMetadata
import dk.cachet.carp.common.application.UUID
import dk.cachet.carp.common.application.data.DataType
import dk.cachet.carp.common.application.data.HeartRate
import dk.cachet.carp.common.application.data.StepCount
import dk.cachet.carp.data.application.DataStreamId
import dk.cachet.carp.data.application.MutableDataStreamBatch
import dk.cachet.carp.data.application.SyncPoint
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataStreamBatchConverterTest {

    private val converter = DataStreamBatchConverter()

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
}
