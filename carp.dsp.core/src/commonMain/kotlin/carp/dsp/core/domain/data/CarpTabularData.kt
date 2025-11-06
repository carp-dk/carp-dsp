package carp.dsp.core.domain.data

import dk.cachet.carp.analytics.domain.data.ICarpTabularData
import dk.cachet.carp.common.application.UUID
import dk.cachet.carp.data.application.DataStreamBatch

/**
 * Tabular representation of CARP data that preserves all metadata and provenance
 * while enabling tabular operations like filtering, grouping, and analysis.
 */
@Suppress("TooManyFunctions")
class CarpTabularData(
    /**
     * List of measurement rows with preserved type information and metadata.
     */
    val rows: List<CarpMeasurementRow>,

    /**
     * Reference to the original DataStreamBatch for full provenance.
     */
    val originalBatch: DataStreamBatch
) : ICarpTabularData{

    /**
     * Total number of measurements in this tabular data.
     */
    val size: Int get() = rows.size

    /**
     * Check if the tabular data is empty.
     */
    val isEmpty: Boolean get() = rows.isEmpty()

    /**
     * Get all unique device role names in this data.
     */
    val deviceRoleNames: Set<String> get() = rows.map { it.metadata.deviceRoleName }.toSet()

    /**
     * Get all unique study deployment IDs in this data.
     */
    val studyDeploymentIds: Set<UUID> get() = rows.map { it.metadata.studyDeploymentId }.toSet()

    /**
     * Get all unique data types in this data.
     */
    val dataTypes: Set<String> get() = rows.map { it.metadata.dataTypeString }.toSet()

    /**
     * Get time range of all measurements.
     */
    val timeRange: LongRange? get() {
        if (rows.isEmpty()) return null
        val timestamps = rows.map { it.sensorTimestamp }
        return timestamps.minOrNull()!!..timestamps.maxOrNull()!!
    }

    // === Filtering Operations ===

    /**
     * Filter rows by device role name.
     */
    fun filterByDeviceRole(deviceRoleName: String): CarpTabularData {
        val filteredRows = rows.filter { it.metadata.deviceRoleName == deviceRoleName }
        return CarpTabularData(filteredRows, originalBatch)
    }

    /**
     * Filter rows by study deployment ID.
     */
    fun filterByStudyDeployment(studyDeploymentId: UUID): CarpTabularData {
        val filteredRows = rows.filter { it.metadata.studyDeploymentId == studyDeploymentId }
        return CarpTabularData(filteredRows, originalBatch)
    }

    /**
     * Filter rows by data type string.
     */
    fun filterByDataType(dataType: String): CarpTabularData {
        val filteredRows = rows.filter { it.metadata.dataTypeString == dataType }
        return CarpTabularData(filteredRows, originalBatch)
    }

    /**
     * Filter rows by specific measurement type using type-safe approach.
     */
    inline fun <reified T : CarpMeasurementRow> filterByMeasurementType(): List<T> {
        return rows.filterIsInstance<T>()
    }

    /**
     * Filter rows by time range.
     */
    fun filterByTimeRange(startTimestamp: Long, endTimestamp: Long): CarpTabularData {
        val filteredRows = rows.filter {
            it.sensorTimestamp >= startTimestamp && it.sensorTimestamp <= endTimestamp
        }
        return CarpTabularData(filteredRows, originalBatch)
    }

    /**
     * Filter rows by sequence index (for provenance-based filtering).
     */
    fun filterBySequence(sequenceIndex: Int): CarpTabularData {
        val filteredRows = rows.filter { it.metadata.sequenceIndex == sequenceIndex }
        return CarpTabularData(filteredRows, originalBatch)
    }

    /**
     * Filter rows by trigger IDs.
     */
    fun filterByTriggerIds(triggerIds: List<Int>): CarpTabularData {
        val filteredRows = rows.filter { row ->
            triggerIds.any { triggerId -> row.metadata.triggerIds.contains(triggerId) }
        }
        return CarpTabularData(filteredRows, originalBatch)
    }

    // === Grouping Operations ===

    /**
     * Group rows by device role name.
     */
    fun groupByDeviceRole(): Map<String, CarpTabularData> {
        return rows.groupBy { it.metadata.deviceRoleName }
            .mapValues { (_, groupedRows) -> CarpTabularData(groupedRows, originalBatch) }
    }

    /**
     * Group rows by study deployment ID.
     */
    fun groupByStudyDeployment(): Map<UUID, CarpTabularData> {
        return rows.groupBy { it.metadata.studyDeploymentId }
            .mapValues { (_, groupedRows) -> CarpTabularData(groupedRows, originalBatch) }
    }

    /**
     * Group rows by data type.
     */
    fun groupByDataType(): Map<String, CarpTabularData> {
        return rows.groupBy { it.metadata.dataTypeString }
            .mapValues { (_, groupedRows) -> CarpTabularData(groupedRows, originalBatch) }
    }

    /**
     * Group rows by sequence index.
     */
    fun groupBySequence(): Map<Int, CarpTabularData> {
        return rows.groupBy { it.metadata.sequenceIndex }
            .mapValues { (_, groupedRows) -> CarpTabularData(groupedRows, originalBatch) }
    }

    /**
     * Group rows by time windows of specified duration in milliseconds.
     */
    fun groupByTimeWindow(windowSizeMs: Long): Map<Long, CarpTabularData> {
        return rows.groupBy { it.sensorTimestamp / windowSizeMs }
            .mapValues { (_, groupedRows) -> CarpTabularData(groupedRows, originalBatch) }
    }

    // === Type-Safe Access ===

    /**
     * Get all StepCount measurements.
     */
    fun getStepCountRows(): List<StepCountMeasurementRow> {
        return filterByMeasurementType<StepCountMeasurementRow>()
    }

    /**
     * Get all Acceleration measurements.
     */
    fun getAccelerationRows(): List<AccelerationMeasurementRow> {
        return filterByMeasurementType<AccelerationMeasurementRow>()
    }


    /**
     * Get all Geolocation measurements.
     */
    fun getGeolocationRows(): List<GeolocationMeasurementRow> {
        return filterByMeasurementType<GeolocationMeasurementRow>()
    }

    /**
     * Get all HeartRate measurements.
     */
    fun getHeartRateRows(): List<HeartRateMeasurementRow> {
        return filterByMeasurementType<HeartRateMeasurementRow>()
    }

    /**
     * Get all Generic measurements (unsupported data types).
     */
    fun getGenericRows(): List<GenericMeasurementRow> {
        return filterByMeasurementType<GenericMeasurementRow>()
    }

    // === Utility Operations ===

    /**
     * Get summary information about this tabular data.
     */
    fun getSummary(): CarpTabularDataSummary {
        val measurementTypeCounts = rows.groupBy { it::class.simpleName }
            .mapValues { (_, rows) -> rows.size }

        return CarpTabularDataSummary(
            totalRows = size,
            deviceRoleCount = deviceRoleNames.size,
            dataTypeCount = dataTypes.size,
            studyDeploymentCount = studyDeploymentIds.size,
            timeRange = timeRange,
            measurementTypeCounts = measurementTypeCounts,
            deviceRoleNames = deviceRoleNames,
            studyDeploymentIds = studyDeploymentIds,
            dataTypes = dataTypes
        )
    }

    /**
     * Convert back to a subset of the original DataStreamBatch containing only the filtered data.
     * This preserves the CARP structure while applying the tabular filtering.
     */
    fun toDataStreamBatch(): DataStreamBatch {
        // This would require reconstructing the batch from the filtered rows
        // Implementation would depend on specific use cases
        TODO("Implementation depends on specific reconstruction requirements")
    }
}

/**
 * Summary information about CarpTabularData.
 */
data class CarpTabularDataSummary(
    val totalRows: Int,
    val deviceRoleCount: Int,
    val dataTypeCount: Int,
    val studyDeploymentCount: Int,
    val timeRange: LongRange?,
    val measurementTypeCounts: Map<String?, Int>,
    val deviceRoleNames: Set<String>,
    val studyDeploymentIds: Set<UUID>,
    val dataTypes: Set<String>
)
