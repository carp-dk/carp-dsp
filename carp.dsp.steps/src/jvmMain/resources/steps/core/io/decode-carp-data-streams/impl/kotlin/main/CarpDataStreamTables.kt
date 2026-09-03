@file:Suppress("PackageDirectoryMismatch")

package carp.dsp.steps.datastream

import carp.dsp.core.application.DataStreamBatchConverter
import carp.dsp.core.domain.data.CarpTabularData
import carp.dsp.core.domain.data.CsvTable
import dk.cachet.carp.data.application.MutableDataStreamBatch
import dk.cachet.carp.data.application.applyToTimestamp

/** Sensor time is microseconds since the epoch; a window is given in milliseconds. */
private const val MICROSECONDS_PER_MILLISECOND = 1000L

/**
 * Returns the rows of [table] as data-stream rows, in the order they appear.
 *
 * @throws IllegalArgumentException when a row is missing a column or holds a
 *   value its column cannot take. The message names the row's position.
 */
fun carpDataStreamRows(table: CsvTable): List<CarpDataStreamRow> =
    table.rows.mapIndexed { position, values ->
        runCatching { carpDataStreamRow(table.columns, values) }
            .getOrElse { failure ->
                throw IllegalArgumentException("Row ${position + 1}: ${failure.message}", failure)
            }
    }

/**
 * Returns [rows] as one table, with the same structure of  `[core.io.fetch-study-data]`.
 *
 * @throws IllegalArgumentException when a snapshot does not decode, or when the
 *   rows do not form a batch - sequences of one stream have to arrive in
 *   sequence order.
 */
fun carpTabularDataOf(rows: List<CarpDataStreamRow>): CarpTabularData
{
    val batch = MutableDataStreamBatch()

    rows.forEach { row ->
        val sequence = row.toSequence()

        runCatching { batch.appendSequence(sequence) }.getOrElse { failure ->
            throw IllegalArgumentException(
                "Could not add $row to the batch: ${failure.message} " +
                    "Sequences of one stream have to arrive in order, so the query needs " +
                    "ORDER BY on the stream and its first sequence id.",
                failure,
            )
        }
    }

    return DataStreamBatchConverter().toTabularData(batch)
}

/**
 * Returns [this] narrowed to the measurements taken in `[fromMs, toMs)`, or
 * [this] when neither bound is given.
 *
 * The bounds are epoch milliseconds and the end is exclusive.
 */
fun CarpTabularData.inWindow(fromMs: Long?, toMs: Long?): CarpTabularData
{
    if (fromMs == null && toMs == null) return this

    val from = fromMs?.times(MICROSECONDS_PER_MILLISECOND)
    val to = toMs?.times(MICROSECONDS_PER_MILLISECOND)

    val kept = rows.filter { row ->
        val at = row.metadata.syncPoint.applyToTimestamp(row.sensorStartTime)
        (from == null || at >= from) && (to == null || at < to)
    }

    return CarpTabularData(kept, originalBatch)
}
