@file:Suppress("SqlSourceToSinkFlow", "PackageDirectoryMismatch") // handled by the [requireSingleReadStatement].

package carp.dsp.steps.sql

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.util.Properties

/**
 * Receives a result set as it is read.
 *
 * [begin] is called once, before any row, whether rows follow. Rows
 * arrive in the order the database returned them and are not retained, so an
 * implementation needing them all keeps them itself.
 */
interface SqlRowSink
{
    /** Announces the result's column labels. */
    fun begin(columns: List<String>)

    /** Accepts one row, `null` where the value was SQL NULL. */
    fun row(values: List<String?>)
}

/**
 * Runs a query against a database and streams the result to a [SqlRowSink].
 *
 * The connection is read-only and is closed before returning, whether the query
 * succeeded or not.
 *
 * @param open How to obtain a connection. The default uses [DriverManager], so
 *   the driver is chosen by the JDBC url and has to be on the classpath.
 */
class JdbcQuerySource(private val open: (SqlConnection) -> Connection = ::connect)
{
    /**
     * Reads [query] from [target], and returns the number of rows read.
     *
     * Rows reach [sink] as they are read, so a read that fails part way may have
     * delivered some: a caller writing a file keeps it until this returns.
     *
     * @throws SqlAccessException when the database refuses the connection, the
     *   credentials or the statement.
     * @throws IllegalStateException when the result breaks [SqlQuery.maxRows] or
     *   is empty and [SqlQuery.requireRows] is set.
     */
    fun read(target: SqlConnection, query: SqlQuery, sink: SqlRowSink): Long =
        try
        {
            open(target).use { connection ->
                connection.isReadOnly = true

                // without this the whole result is in memory before the syncing.
                connection.autoCommit = false

                connection.prepareStatement(query.sql).use { statement ->
                    statement.fetchSize = query.fetchSize

                    // One more than the cap so the drain can check it rather than truncating.
                    query.maxRows?.let { statement.maxRows = it + 1 }

                    query.params.forEachIndexed { index, value -> statement.setString(index + 1, value) }

                    statement.executeQuery().use { rows -> drain(rows, query, sink) }
                }
            }
        }
        catch (failure: SQLException)
        {
            throw sqlFailure(failure, target)
        }

    private fun drain(rows: ResultSet, query: SqlQuery, sink: SqlRowSink): Long
    {
        // The JDBC API is 1-based, so the first column is 1, not 0.
        // The sink is 0-based, so the first value is 0, not 1.
        // The indices are used to read the values and to match them with the labels.
        val columns = rows.metaData.let { meta -> (1..meta.columnCount).map { meta.getColumnLabel(it) } }
        sink.begin(columns)

        var read = 0L
        while (rows.next())
        {
            val cap = query.maxRows
            check(cap == null || read < cap)
            {
                "The query returned more than $cap rows. Narrow it, or raise the row cap."
            }

            sink.row(columns.indices.map { rows.getString(it + 1) })
            read++
        }

        check(read > 0 || !query.requireRows)
        {
            "The query returned no rows. Check its filters and parameters: an empty table " +
                "downstream is harder to notice than a failure here."
        }

        return read
    }
}

/** Opens a connection through [DriverManager]. */
private fun connect(target: SqlConnection): Connection =
    DriverManager.getConnection(
        target.url,
        Properties().apply {
            target.user?.let { setProperty("user", it) }
            target.password?.let { setProperty("password", it) }
        },
    )
