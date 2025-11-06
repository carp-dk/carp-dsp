package carp.dsp.core.application

import carp.dsp.core.domain.data.AccelerationMeasurementRow
import carp.dsp.core.domain.data.CarpMeasurementMetadata
import carp.dsp.core.domain.data.CarpMeasurementRow
import carp.dsp.core.domain.data.CarpTabularData
import carp.dsp.core.domain.data.GenericMeasurementRow
import carp.dsp.core.domain.data.GeolocationMeasurementRow
import carp.dsp.core.domain.data.HeartRateMeasurementRow
import carp.dsp.core.domain.data.StepCountMeasurementRow
import dk.cachet.carp.common.application.UUID
import dk.cachet.carp.common.application.data.*
import dk.cachet.carp.data.application.DataStreamBatch

/**
 * Converter that transforms CARP DataStreamBatch into tabular format
 * while preserving all metadata and provenance information.
 */
class DataStreamBatchConverter {

    /**
     * Convert a DataStreamBatch to CarpTabularData.
     *
     * This method iterates through all sequences and measurements in the batch,
     * creating typed measurement rows while preserving all original metadata.
     */
    fun toTabularData(batch: DataStreamBatch): CarpTabularData {
        val rows = mutableListOf<CarpMeasurementRow>()

        batch.sequences.forEachIndexed { sequenceIndex, sequence ->
            sequence.measurements.forEachIndexed { measurementIndex, measurement ->
                val metadata = CarpMeasurementMetadata(
                    sequenceIndex = sequenceIndex,
                    measurementIndex = measurementIndex,
                    dataStreamId = sequence.dataStream,
                    firstSequenceId = sequence.firstSequenceId,
                    triggerIds = sequence.triggerIds,
                    syncPoint = sequence.syncPoint
                )

                val row = createTypedMeasurementRow(
                    sensorStartTime = measurement.sensorStartTime,
                    sensorEndTime = measurement.sensorEndTime,
                    dataType = measurement.dataType,
                    data = measurement.data,
                    metadata = metadata
                )

                rows.add(row)
            }
        }

        return CarpTabularData(rows, batch)
    }

    /**
     * Create a typed measurement row based on the data type.
     * Uses pattern matching to create the appropriate row type.
     */
    private fun createTypedMeasurementRow(
        sensorStartTime: Long,
        sensorEndTime: Long?,
        dataType: DataType,
        data: Data,
        metadata: CarpMeasurementMetadata
    ): CarpMeasurementRow {
        return when (data) {
            is StepCount -> StepCountMeasurementRow(
                sensorStartTime = sensorStartTime,
                sensorEndTime = sensorEndTime,
                dataType = dataType,
                metadata = metadata,
                stepCount = data
            )

            is Acceleration -> AccelerationMeasurementRow(
                sensorStartTime = sensorStartTime,
                sensorEndTime = sensorEndTime,
                dataType = dataType,
                metadata = metadata,
                acceleration = data
            )


            is Geolocation -> GeolocationMeasurementRow(
                sensorStartTime = sensorStartTime,
                sensorEndTime = sensorEndTime,
                dataType = dataType,
                metadata = metadata,
                geolocation = data
            )

            is HeartRate -> HeartRateMeasurementRow(
                sensorStartTime = sensorStartTime,
                sensorEndTime = sensorEndTime,
                dataType = dataType,
                metadata = metadata,
                heartRate = data
            )

            else -> GenericMeasurementRow(
                sensorStartTime = sensorStartTime,
                sensorEndTime = sensorEndTime,
                dataType = dataType,
                metadata = metadata,
                originalData = data
            )
        }
    }

    /**
     * Convert specific data types to tabular format.
     * Useful when you only want data of a specific type.
     */
    inline fun <reified T : Data> toTypedTabularData(batch: DataStreamBatch): CarpTabularData {
        val tabularData = toTabularData(batch)
        val filteredRows = tabularData.rows.filter { row ->
            row.originalData is T
        }
        return CarpTabularData(filteredRows, batch)
    }

    /**
     * Convert and filter by device role name.
     */
    fun toTabularDataForDeviceRole(batch: DataStreamBatch, deviceRoleName: String): CarpTabularData {
        return toTabularData(batch).filterByDeviceRole(deviceRoleName)
    }

    /**
     * Convert and filter by study deployment ID.
     */
    fun toTabularDataForStudyDeployment(batch: DataStreamBatch, studyDeploymentId: UUID): CarpTabularData {
        return toTabularData(batch).filterByStudyDeployment(studyDeploymentId)
    }

    /**
     * Convert and filter by time range.
     */
    fun toTabularDataForTimeRange(
        batch: DataStreamBatch,
        startTimestamp: Long,
        endTimestamp: Long
    ): CarpTabularData {
        return toTabularData(batch).filterByTimeRange(startTimestamp, endTimestamp)
    }
}
