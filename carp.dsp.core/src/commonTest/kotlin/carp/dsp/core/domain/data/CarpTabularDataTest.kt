package carp.dsp.core.domain.data

import carp.dsp.core.domain.data.AccelerationMeasurementRow
import carp.dsp.core.domain.data.CarpMeasurementMetadata
import carp.dsp.core.domain.data.CarpMeasurementRow
import carp.dsp.core.domain.data.CarpTabularData
import carp.dsp.core.domain.data.HeartRateMeasurementRow
import carp.dsp.core.domain.data.StepCountMeasurementRow
import dk.cachet.carp.common.application.UUID
import dk.cachet.carp.common.application.data.Acceleration
import dk.cachet.carp.common.application.data.DataType
import dk.cachet.carp.common.application.data.HeartRate
import dk.cachet.carp.common.application.data.StepCount
import dk.cachet.carp.data.application.DataStreamId
import dk.cachet.carp.data.application.MutableDataStreamBatch
import dk.cachet.carp.data.application.SyncPoint
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CarpTabularDataTest {

    private fun createTestRows(): List<CarpMeasurementRow> {
        val studyDeploymentId1 = UUID.randomUUID()
        val studyDeploymentId2 = UUID.randomUUID()

        val phoneDataStreamId = DataStreamId(studyDeploymentId1, "phone", DataType("dk.cachet.carp", "step_count"))
        val watchDataStreamId = DataStreamId(studyDeploymentId1, "watch", DataType("dk.cachet.carp", "heart_rate"))
        val tabletDataStreamId = DataStreamId(studyDeploymentId2, "tablet", DataType("dk.cachet.carp", "acceleration"))

        val syncPoint = SyncPoint(Instant.fromEpochMilliseconds(1000L), 1L)

        val phoneMetadata = CarpMeasurementMetadata(0, 0, phoneDataStreamId, 100L, listOf(1), syncPoint)
        val watchMetadata = CarpMeasurementMetadata(1, 0, watchDataStreamId, 200L, listOf(2), syncPoint)
        val tabletMetadata = CarpMeasurementMetadata(2, 0, tabletDataStreamId, 300L, listOf(3), syncPoint)

        return listOf(
            StepCountMeasurementRow(
                1000L, null, DataType("dk.cachet.carp", "step_count"), phoneMetadata, StepCount(1500)
            ),
            StepCountMeasurementRow(
                2000L, null, DataType("dk.cachet.carp", "step_count"), phoneMetadata, StepCount(1600)
            ),
            HeartRateMeasurementRow(
                1500L, 2500L, DataType("dk.cachet.carp", "heart_rate"), watchMetadata, HeartRate(75)
            ),
            AccelerationMeasurementRow(
                3000L, null, DataType("dk.cachet.carp", "acceleration"), tabletMetadata, Acceleration(1.0, 2.0, 3.0)
            )
        )
    }

    private fun createTestTabularData(): CarpTabularData {
        val rows = createTestRows()
        val mockBatch = MutableDataStreamBatch()
        return CarpTabularData(rows, mockBatch)
    }

    @Test
    fun testBasicProperties() {
        val tabularData = createTestTabularData()

        assertEquals(4, tabularData.size)
        assertFalse(tabularData.isEmpty)
        assertEquals(setOf("phone", "watch", "tablet"), tabularData.deviceRoleNames)
        assertEquals(setOf("dk.cachet.carp.step_count", "dk.cachet.carp.heart_rate", "dk.cachet.carp.acceleration"), tabularData.dataTypes)
        assertEquals(2, tabularData.studyDeploymentIds.size)
    }

    @Test
    fun testTimeRange() {
        val tabularData = createTestTabularData()
        val timeRange = tabularData.timeRange

        assertEquals(1000L..3000L, timeRange)
    }

    @Test
    fun testFilterByDeviceRole() {
        val tabularData = createTestTabularData()

        val phoneData = tabularData.filterByDeviceRole("phone")
        assertEquals(2, phoneData.size)
        assertTrue(phoneData.rows.all { it.metadata.deviceRoleName == "phone" })

        val watchData = tabularData.filterByDeviceRole("watch")
        assertEquals(1, watchData.size)
        assertTrue(watchData.rows.all { it.metadata.deviceRoleName == "watch" })

        val nonExistentData = tabularData.filterByDeviceRole("nonexistent")
        assertEquals(0, nonExistentData.size)
    }

    @Test
    fun testFilterByDataType() {
        val tabularData = createTestTabularData()

        val stepCountData = tabularData.filterByDataType("dk.cachet.carp.step_count")
        assertEquals(2, stepCountData.size)
        assertTrue(stepCountData.rows.all { it.metadata.dataTypeString == "dk.cachet.carp.step_count" })

        val heartRateData = tabularData.filterByDataType("dk.cachet.carp.heart_rate")
        assertEquals(1, heartRateData.size)
        assertTrue(heartRateData.rows.all { it.metadata.dataTypeString == "dk.cachet.carp.heart_rate" })
    }

    @Test
    fun testFilterByTimeRange() {
        val tabularData = createTestTabularData()

        val earlyData = tabularData.filterByTimeRange(1000L, 2000L)
        assertEquals(3, earlyData.size) // Should include measurements at 1000L and 2000L

        val lateData = tabularData.filterByTimeRange(2500L, 4000L)
        assertEquals(1, lateData.size) // Should include measurement at 3000L

        val noData = tabularData.filterByTimeRange(5000L, 6000L)
        assertEquals(0, noData.size)
    }

    @Test
    fun testFilterBySequence() {
        val tabularData = createTestTabularData()

        val sequence0 = tabularData.filterBySequence(0)
        assertEquals(2, sequence0.size) // Both StepCount measurements

        val sequence1 = tabularData.filterBySequence(1)
        assertEquals(1, sequence1.size) // HeartRate measurement

        val sequence2 = tabularData.filterBySequence(2)
        assertEquals(1, sequence2.size) // Acceleration measurement
    }

    @Test
    fun testGroupByDeviceRole() {
        val tabularData = createTestTabularData()
        val grouped = tabularData.groupByDeviceRole()

        assertEquals(3, grouped.size)
        assertEquals(2, grouped["phone"]?.size)
        assertEquals(1, grouped["watch"]?.size)
        assertEquals(1, grouped["tablet"]?.size)
    }

    @Test
    fun testGroupByDataType() {
        val tabularData = createTestTabularData()
        val grouped = tabularData.groupByDataType()

        assertEquals(3, grouped.size)
        assertEquals(2, grouped["dk.cachet.carp.step_count"]?.size)
        assertEquals(1, grouped["dk.cachet.carp.heart_rate"]?.size)
        assertEquals(1, grouped["dk.cachet.carp.acceleration"]?.size)
    }

    @Test
    fun testGroupByStudyDeployment() {
        val tabularData = createTestTabularData()
        val grouped = tabularData.groupByStudyDeployment()

        assertEquals(2, grouped.size)
        // One deployment should have 3 measurements (phone + watch), other should have 1 (tablet)
        val deploymentSizes = grouped.values.map { it.size }.sorted()
        assertEquals(listOf(1, 3), deploymentSizes)
    }

    @Test
    fun testGroupByTimeWindow() {
        val tabularData = createTestTabularData()
        val grouped = tabularData.groupByTimeWindow(1000L) // 1 second windows

        // Measurements at 1000L, 1500L, 2000L should be in windows 1, 1, 2
        // Measurement at 3000L should be in window 3
        assertTrue(grouped.size >= 2)
    }

    @Test
    fun testTypeSpecificAccess() {
        val tabularData = createTestTabularData()

        val stepCountRows = tabularData.getStepCountRows()
        assertEquals(2, stepCountRows.size)

        val heartRateRows = tabularData.getHeartRateRows()
        assertEquals(1, heartRateRows.size)

        val accelerationRows = tabularData.getAccelerationRows()
        assertEquals(1, accelerationRows.size)

        val geolocationRows = tabularData.getGeolocationRows()
        assertEquals(0, geolocationRows.size)
    }

    @Test
    fun testSummary() {
        val tabularData = createTestTabularData()
        val summary = tabularData.getSummary()

        assertEquals(4, summary.totalRows)
        assertEquals(3, summary.deviceRoleCount)
        assertEquals(3, summary.dataTypeCount)
        assertEquals(2, summary.studyDeploymentCount)
        assertEquals(1000L..3000L, summary.timeRange)
        assertEquals(setOf("phone", "watch", "tablet"), summary.deviceRoleNames)
        assertEquals(setOf("dk.cachet.carp.step_count", "dk.cachet.carp.heart_rate", "dk.cachet.carp.acceleration"), summary.dataTypes)
        assertEquals(2, summary.studyDeploymentIds.size)

        // Check measurement type counts
        assertTrue(summary.measurementTypeCounts.containsKey("StepCountMeasurementRow"))
        assertTrue(summary.measurementTypeCounts.containsKey("HeartRateMeasurementRow"))
        assertTrue(summary.measurementTypeCounts.containsKey("AccelerationMeasurementRow"))
    }

    @Test
    fun testEmptyTabularData() {
        val emptyData = CarpTabularData(emptyList<CarpMeasurementRow>(), MutableDataStreamBatch())

        assertEquals(0, emptyData.size)
        assertTrue(emptyData.isEmpty)
        assertTrue(emptyData.deviceRoleNames.isEmpty())
        assertTrue(emptyData.dataTypes.isEmpty())
        assertTrue(emptyData.studyDeploymentIds.isEmpty())
        assertEquals(null, emptyData.timeRange)

        val summary = emptyData.getSummary()
        assertEquals(0, summary.totalRows)
        assertEquals(null, summary.timeRange)
    }
}
