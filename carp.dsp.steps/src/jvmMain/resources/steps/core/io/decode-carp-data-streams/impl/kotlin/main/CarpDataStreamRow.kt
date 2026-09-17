@file:Suppress("PackageDirectoryMismatch")

package carp.dsp.steps.datastream

import dk.cachet.carp.common.application.UUID
import dk.cachet.carp.common.application.data.Data
import dk.cachet.carp.common.application.data.DataType
import dk.cachet.carp.data.application.DataStreamId
import dk.cachet.carp.data.application.DataStreamSequence
import dk.cachet.carp.data.application.MutableDataStreamSequence

/** The columns a data-stream query must produce, whatever it joins to get them. */
object CarpDataStreamColumns
{
    const val STUDY_DEPLOYMENT_ID: String = "study_deployment_id"
    const val DEVICE_ROLE_NAME: String = "device_role_name"
    const val DATA_TYPE: String = "data_type"
    const val FIRST_SEQUENCE_ID: String = "first_sequence_id"
    const val SNAPSHOT: String = "snapshot"

    val ALL: List<String> = listOf(
        STUDY_DEPLOYMENT_ID,
        DEVICE_ROLE_NAME,
        DATA_TYPE,
        FIRST_SEQUENCE_ID,
        SNAPSHOT,
    )
}

/**
 * One row of a data-stream query: a stored snapshot, with the identity the
 * database keeps outside it.
 */
data class CarpDataStreamRow(
    val studyDeploymentId: UUID,
    val deviceRoleName: String,
    val dataType: DataType,
    val firstSequenceId: Long,
    val snapshot: String,
)
{
    /** Names this row in a failure, without repeating its measurements. */
    override fun toString(): String =
        "$studyDeploymentId/$deviceRoleName/$dataType from sequence $firstSequenceId"
}

/**
 * Returns the row [values] describe, keyed by [columns].
 *
 * @throws IllegalArgumentException when a column of [CarpDataStreamColumns] is
 *   absent or empty, or a value is not of the type its column implies.
 */
fun carpDataStreamRow(columns: List<String>, values: List<String?>): CarpDataStreamRow
{
    val row = columns.zip(values).toMap()

    val missing = CarpDataStreamColumns.ALL - row.keys
    require(missing.isEmpty())
    {
        "The query must select ${CarpDataStreamColumns.ALL}, and did not select ${missing.sorted()}. " +
            "Alias the columns if the query names them differently."
    }

    fun read(column: String): String =
        requireNotNull(row[column]?.takeIf { it.isNotBlank() }) { "'$column' is empty in this row." }

    return CarpDataStreamRow(
        studyDeploymentId = UUID.parse(read(CarpDataStreamColumns.STUDY_DEPLOYMENT_ID)),
        deviceRoleName = read(CarpDataStreamColumns.DEVICE_ROLE_NAME),
        dataType = DataType.fromString(read(CarpDataStreamColumns.DATA_TYPE)),
        firstSequenceId = read(CarpDataStreamColumns.FIRST_SEQUENCE_ID).toLongOrNull()
            ?: throw IllegalArgumentException(
                "'${CarpDataStreamColumns.FIRST_SEQUENCE_ID}' is not a whole number in this row."
            ),
        snapshot = read(CarpDataStreamColumns.SNAPSHOT),
    )
}

/**
 * Returns row as a sequence, putting the columns the database keeps outside
 * the snapshot back together with the measurements inside it.
 *
 * @throws IllegalArgumentException when the snapshot does not decode, or carries
 *   a measurement of a data type other than the row's.
 */
fun CarpDataStreamRow.toSequence(): DataStreamSequence<Data>
{
    val decoded = runCatching { CARP_JSON.decodeFromString<CarpDataStreamSnapshot>(snapshot) }
        .getOrElse { failure ->
            throw IllegalArgumentException("The snapshot of $this does not decode: ${failure.message}", failure)
        }

    return MutableDataStreamSequence<Data>(
        DataStreamId(studyDeploymentId, deviceRoleName, dataType),
        firstSequenceId,
        decoded.triggerIds,
        decoded.syncPoint,
    ).apply {
        // Rejects a snapshot whose measurements disagree with the row's data type.
        appendMeasurements(decoded.measurements)
    }
}
