@file:Suppress("PackageDirectoryMismatch")

package carp.dsp.steps.sql

import carp.dsp.core.domain.data.Csv
import java.io.Writer

/**
 * Writes a result set to [out] as CSV, a row at a time.
 *
 * SQL NULL becomes an empty cell, which is what a table means by absent.
 * Quoting is [Csv]'s, so what this writes is what the library reads back.
 */
class CsvRowSink(private val out: Writer) : SqlRowSink
{
    override fun begin(columns: List<String>) = write(columns)

    override fun row(values: List<String?>) = write(values.map { it.orEmpty() })

    private fun write(cells: List<String>)
    {
        out.write(Csv.row(cells))
        out.write("\n")
    }
}
