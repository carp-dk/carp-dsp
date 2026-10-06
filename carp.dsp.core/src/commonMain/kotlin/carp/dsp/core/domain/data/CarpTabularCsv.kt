package carp.dsp.core.domain.data

import dk.cachet.carp.common.application.data.Data
import dk.cachet.carp.common.infrastructure.serialization.DataSerializer
import dk.cachet.carp.common.infrastructure.serialization.createDefaultJSON
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

private const val ROW_ID = "row_id"
private const val TYPE_KEY = "__type"
private const val CARP_PREFIX = "dk.cachet.carp."

/**
 * Writes [CarpTabularData] as CSV.
 *
 * Measurements and provenance are written as separate CSV tables linked by
 * [ROW_ID].
 */
object CarpTabularCsv
{
    /**
     * Returns a CSV table containing measurement data.
     *
     * Each row represents a single measurement. Value columns are derived from the
     * data types present in [data].
     *
     * @param columns The value columns to include. When omitted, all value columns
     * present in [data] are included.
     */
    fun measurements(data: CarpTabularData, columns: List<String>? = null): String
    {
        val valueColumns = columns ?: valueColumnsOf(data)
        val header = listOf(ROW_ID, "data_type", "sensor_start_time", "sensor_end_time", "duration_ms") +
            valueColumns

        return Csv.table(
            header,
            data.rows.map { row ->
            val values = namedValuesOf(row)
            listOf(
                rowId(row),
                row.metadata.dataTypeString,
                row.sensorStartTime.toString(),
                row.sensorEndTime?.toString().orEmpty(),
                row.duration?.toString().orEmpty(),
            ) + valueColumns.map { values[it].orEmpty() }
        }
        )
    }

    /**
     * Returns a CSV table containing measurement provenance.
     *
     * Rows are keyed by [ROW_ID] and correspond one-to-one with the rows produced
     * by [measurements].
     */
    fun provenance(data: CarpTabularData): String
    {
        val header = listOf(
            ROW_ID, "study_deployment_id", "device_role_name", "data_type",
            "sequence_index", "measurement_index", "first_sequence_id", "trigger_ids",
            "sync_synchronized_on_ms", "sync_sensor_timestamp", "sync_relative_clock_speed",
        )

        return Csv.table(
            header,
            data.rows.map { row ->
            val meta = row.metadata
            listOf(
                rowId(row),
                meta.studyDeploymentId.toString(),
                meta.deviceRoleName,
                meta.dataTypeString,
                meta.sequenceIndex.toString(),
                meta.measurementIndex.toString(),
                meta.firstSequenceId.toString(),
                // One cell, not one column per trigger: the count varies by row.
                meta.triggerIds.joinToString(" "),
                meta.syncPoint.synchronizedOn.toEpochMilliseconds().toString(),
                meta.syncPoint.sensorTimestampAtSyncPoint.toString(),
                meta.syncPoint.relativeClockSpeed.toString(),
            )
        }
        )
    }

    /** Every value column the rows in [data] carry, sorted. */
    fun valueColumnsOf(data: CarpTabularData): List<String> =
        data.rows.flatMap { namedValuesOf(it).keys }.distinct().sorted()

    /**
     * `sequenceIndex:measurementIndex`, which is already unique across a batch —
     * no new identity to invent, and it points back into the original
     * `DataStreamBatch`.
     */
    private fun rowId(row: CarpMeasurementRow): String =
        "${row.metadata.sequenceIndex}:${row.metadata.measurementIndex}"

    /**
     * Returns a row's values keyed by fully qualified column name.
     *
     * The prefix is the data type without CARP's own namespace: `stepcount.steps`,
     * but `completedapptask.survey.taskData`, which would otherwise share columns
     * with `survey`.
     */
    private fun namedValuesOf(row: CarpMeasurementRow): Map<String, String>
    {
        val prefix = "${row.dataType.namespace}.${row.dataType.name}".removePrefix(CARP_PREFIX)
        return valuesOf(row).mapKeys { (field, _) -> "$prefix.$field" }
    }

    /**
     * Returns the values recorded for a measurement row.
     */
    private fun valuesOf(row: CarpMeasurementRow): Map<String, String> = when (row)
    {
        is StepCountMeasurementRow -> mapOf("steps" to row.steps.toString())

        is AccelerationMeasurementRow -> mapOf(
            "x" to row.x.toString(),
            "y" to row.y.toString(),
            "z" to row.z.toString(),
        )

        is GeolocationMeasurementRow -> mapOf(
            "latitude" to row.latitude.toString(),
            "longitude" to row.longitude.toString(),
        )

        is HeartRateMeasurementRow -> mapOf("bpm" to row.bpm.toString())

        // Any other type gets a column per top-level field, read off its JSON, so
        // a new data type needs no code here.
        is GenericMeasurementRow -> fieldsOf(row.originalData)
    }

    private val json = createDefaultJSON()

    /**
     * Returns the top-level fields of [data], keyed by field name.
     *
     * A scalar is written as its value, a nested object or array as its JSON, and
     * a null as an empty cell. The type discriminator is left out; the row
     * already carries the type.
     */
    private fun fieldsOf(data: Data): Map<String, String> =
        // A type carp-core does not know - every CAMS type - is held as its original
        // JSON, and the serializer gives that back unchanged.
        json.encodeToJsonElement(DataSerializer, data).jsonObject
            .filterKeys { it != TYPE_KEY }
            .mapValues { (_, value) -> cellOf(value) }

    private fun cellOf(value: JsonElement): String = when (value) {
        is JsonNull -> ""
        is JsonPrimitive -> value.content
        else -> value.toString()
    }
}
