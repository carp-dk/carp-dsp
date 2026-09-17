@file:Suppress("PackageDirectoryMismatch")

package carp.dsp.steps.datastream

import carp.dsp.core.domain.data.CarpTabularCsv
import carp.dsp.core.domain.data.Csv
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Turns a queried data-stream table into the [CarpTabularCsv] format. */
class DecodeCarpDataStreamsStep
{
    /**
     * Reads [input] and writes the measurement table to [measurements], and the
     * provenance table to [provenance] when one is given. Returns the number of
     * measurement rows written.
     *
     * [fromMs] and [toMs] narrow the result to `[fromMs, toMs)` in epoch
     * milliseconds; omitting either leaves that end open.
     *
     * @throws IllegalArgumentException when [input] is not a readable table of
     *   data-stream rows, a snapshot does not decode, or [fromMs] is after
     *   [toMs].
     * @throws IllegalStateException when the input holds no rows, or none of
     *   them fall in the window.
     */
    fun run(
        input: File,
        measurements: File,
        provenance: File? = null,
        fromMs: Long? = null,
        toMs: Long? = null,
    ): Long
    {
        require(input.isFile) { "No table to decode at ${input.path}." }
        require(fromMs == null || toMs == null || fromMs <= toMs)
        {
            "The window starts at $fromMs, which is after it ends at $toMs."
        }

        val rows = carpDataStreamRows(Csv.read(input.readText()))
        check(rows.isNotEmpty())
        {
            "The table at ${input.path} holds no rows. An empty measurement table downstream " +
                "is harder to notice than a failure here."
        }

        val data = carpTabularDataOf(rows).inWindow(fromMs, toMs)
        check(data.rows.isNotEmpty())
        {
            "No measurement of the ${rows.size} sequence(s) read falls in " +
                "${fromMs ?: "(open)"}..${toMs ?: "(open)"}. The window is epoch milliseconds, " +
                "its end is exclusive, and it is compared against synchronised time."
        }

        writeWhole(measurements, CarpTabularCsv.measurements(data))
        provenance?.let { writeWhole(it, CarpTabularCsv.provenance(data)) }

        return data.rows.size.toLong()
    }
}

/**
 * Writes [text] to [file], through a neighbour that is moved into place, so the
 * file is never seen half-written.
 */
private fun writeWhole(file: File, text: String)
{
    val directory = file.absoluteFile.parentFile
    directory.mkdirs()

    val partial = File.createTempFile(file.name, ".partial", directory)
    try
    {
        partial.writeText(text)
        Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    finally
    {
        partial.delete()
    }
}
