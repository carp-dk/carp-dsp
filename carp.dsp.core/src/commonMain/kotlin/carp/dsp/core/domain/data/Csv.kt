package carp.dsp.core.domain.data

private const val QUOTE = '"'
private val MUST_QUOTE = charArrayOf(',', '"', '\n', '\r')

/** A table read from CSV: its column names, and its rows in order. */
data class CsvTable(val columns: List<String>, val rows: List<List<String?>>)

/**
 * CSV as this library reads and writes it: RFC 4180, comma separated.
 */
object Csv
{
    const val SEPARATOR: String = ","

    /** Returns [cells] as one line, without a line separator. */
    fun row(cells: List<String>): String = cells.joinToString(SEPARATOR) { escape(it) }

    /** Returns [header] and [rows] as a table, every line ended. */
    fun table(header: List<String>, rows: List<List<String>>): String =
        (listOf(header) + rows).joinToString("\n") { row(it) } + "\n"

    /** Quotes [cell] when it holds a separator, a quote or a newline, doubling its quotes. */
    fun escape(cell: String): String =
        if (cell.any { it in MUST_QUOTE })
        {
            "$QUOTE" + cell.replace("$QUOTE", "$QUOTE$QUOTE") + "$QUOTE"
        }
        else cell

    /**
     * Reads [text] as CSV, taking the first row as the header.
     *
     * An empty cell reads as `null`. A value that was an empty string is
     * indistinguishable from one that was absent, because CSV cannot tell them
     * apart.
     *
     * @throws IllegalArgumentException when [text] has no header row, or a row
     *   holds a different number of cells than the header.
     */
    @Suppress("CyclomaticComplexMethod")
    fun read(text: String): CsvTable
    {
        val rows = mutableListOf<List<String?>>()
        val row = mutableListOf<String?>()
        val cell = StringBuilder()
        var inQuotes = false
        var wasQuoted = false

        fun endCell()
        {
            row += if (!wasQuoted && cell.isEmpty()) null else cell.toString()
            cell.clear()
            wasQuoted = false
        }

        fun endRow()
        {
            endCell()
            rows += row.toList()
            row.clear()
        }

        var index = 0
        while (index < text.length)
        {
            val char = text[index]
            when
            {
                inQuotes ->
                    when
                {
                    // A doubled quote is one quote; a single one ends the cell.
                    char == QUOTE && text.getOrNull(index + 1) == QUOTE -> {
                        cell.append(QUOTE)
                    index++
                    }
                    char == QUOTE -> inQuotes = false
                    else -> cell.append(char)
                }

                char == QUOTE -> {
                    inQuotes = true
                wasQuoted = true
                }
                char.toString() == SEPARATOR -> endCell()
                char == '\r' -> Unit // CRLF: the newline that follows ends the row.
                char == '\n' -> endRow()
                else -> cell.append(char)
            }
            index++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) endRow()

        require(rows.isNotEmpty()) { "The table has no header row." }

        val columns = rows.first().map { it.orEmpty() }
        val body = rows.drop(1)

        body.forEachIndexed { position, cells ->
            require(cells.size == columns.size)
            {
                "Row ${position + 1} has ${cells.size} cells but the header has ${columns.size}."
            }
        }

        return CsvTable(columns, body)
    }
}
