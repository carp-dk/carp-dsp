@file:Suppress("PackageDirectoryMismatch")

package carp.dsp.steps.sql

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Runs a query and writes the result as a CSV file. */
class QuerySqlStep(private val source: JdbcQuerySource = JdbcQuerySource())
{
    /**
     * Writes the result of [query] against [target] to [output], and returns the
     * number of rows written.
     *
     * [output] appears only once the whole result has been read, so a read that
     * fails part way leaves no partial table for a later step to consume.
     *
     * @throws SqlAccessException when the database refuses the connection, the
     *   credentials or the statement.
     * @throws IllegalStateException when the result breaks the query's row cap or
     *   is empty and the query requires rows.
     */
    fun run(target: SqlConnection, query: SqlQuery, output: File): Long
    {
        val directory = output.absoluteFile.parentFile
        directory.mkdirs()

        val partial = File.createTempFile(output.name, ".partial", directory)

        return try
        {
            val rows = partial.bufferedWriter().use { source.read(target, query, CsvRowSink(it)) }
            Files.move(partial.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
            rows
        }
        finally
        {
            partial.delete()
        }
    }
}
