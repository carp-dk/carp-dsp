@file:Suppress("PackageDirectoryMismatch")

package carp.dsp.steps.datastream

import carp.dsp.core.application.DataStreamBatchConverter
import carp.dsp.core.domain.data.CarpTabularData
import carp.dsp.core.domain.data.CsvTable
import dk.cachet.carp.data.application.DataStreamSequence
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
 * Sequences are put in order within each stream, and a sequence whose range lies
 * inside another of the same stream - a re-upload - is left out, as the database
 * query does. Of two identical ranges the first row is kept. So rows read in
 * several batches decode as one read would.
 *
 * @throws IllegalArgumentException when a snapshot does not decode, or when two
 *   sequences of one stream overlap without one holding the other.
 */
fun carpTabularDataOf(rows: List<CarpDataStreamRow>): CarpTabularData
{
    val batch = MutableDataStreamBatch()

    // One stream decoded at a time, so only its sequences are held beside the batch.
    rows.groupBy { Triple(it.studyDeploymentId, it.deviceRoleName, it.dataType) }.values.forEach { stream ->
        withoutCovered(stream.map { row -> row to row.toSequence() }).forEach { (row, sequence) ->
            runCatching { batch.appendSequence(sequence) }.getOrElse { failure ->
                throw IllegalArgumentException(
                    "Could not add $row to the batch: ${failure.message} " +
                        "It overlaps another sequence of the same stream without holding it or being held by it.",
                    failure,
                )
            }
        }
    }

    return DataStreamBatchConverter().toTabularData(batch)
}

/** Returns one stream's sequences in range order, without those another one already holds. */
private fun <T> withoutCovered(stream: List<Pair<T, DataStreamSequence<*>>>): List<Pair<T, DataStreamSequence<*>>>
{
    var reached = Long.MIN_VALUE
    return stream
        .sortedWith(compareBy<Pair<T, DataStreamSequence<*>>> { it.second.range.first }.thenByDescending { it.second.range.last })
        .filter { (_, sequence) ->
            val range = sequence.range
            (range.isEmpty() || range.last > reached).also { kept -> if (kept && !range.isEmpty()) reached = range.last }
        }
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
