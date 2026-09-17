@file:Suppress("PackageDirectoryMismatch")

package carp.dsp.steps.sql

import java.io.File

/** Rows read before the driver is asked for more. */
const val DEFAULT_FETCH_SIZE: Int = 1000

/**
 * Where to connect, and as whom.
 *
 * @property url A JDBC URL, which decides the driver.
 */
data class SqlConnection(
    val url: String,
    val user: String? = null,
    val password: String? = null,
)
{
    init { require(url.isNotBlank()) { "A JDBC url is required." } }

    /**
     * Scrubs the password, and the URL's query string.
     * This is done to prevent sensitive information from being logged or displayed.
     */
    override fun toString(): String = "SqlConnection(url=${url.substringBefore('?')}, user=$user)"
}

/**
 * A single read statement to run.
 *
 * The statement is checked on construction, so a value of this type cannot
 * carry something that writes.
 *
 * @property params Values for the statement's `?` placeholders, in order. Bound
 *   as strings, so the database does the conversion.
 * @property fetchSize Rows the driver reads at a time.
 * @property maxRows Most rows the result may hold. Exceeding it fails the read
 *   rather than truncating: a table that silently depends on a limit is worse
 *   than one that does not arrive. Unset means no cap.
 * @property requireRows Whether an empty result fails. A query matching nothing
 *   is usually a mis-scoped query, and a header-only table hides that.
 * @throws IllegalArgumentException when [sql] is not one statement that reads.
 */
data class SqlQuery(
    val sql: String,
    val params: List<String?> = emptyList(),
    val fetchSize: Int = DEFAULT_FETCH_SIZE,
    val maxRows: Int? = null,
    val requireRows: Boolean = true,
)
{
    init
    {
        require(sql.isNotBlank()) { "A statement is required." }
        requireSingleReadStatement(sql)
        require(fetchSize > 0) { "A fetch size must be positive, was $fetchSize." }
        require(maxRows == null || maxRows in 1 until Int.MAX_VALUE)
        {
            "A row cap must be positive, was $maxRows."
        }
    }
}

/**
 * Returns the statement in [file], with [values] bound to its `:name`
 * placeholders.
 *
 * Every placeholder needs a value and every value needs a placeholder: a name
 * on one side and not the other is a mistake worth reporting rather than a
 * parameter that quietly does nothing.
 *
 * @throws IllegalArgumentException when [file] is not a file, a placeholder has
 *   no value, a value names no placeholder, or the statement is not a single
 *   read.
 */
fun sqlQueryFrom(
    file: File,
    values: Map<String, String?> = emptyMap(),
    fetchSize: Int = DEFAULT_FETCH_SIZE,
    maxRows: Int? = null,
): SqlQuery
{
    require(file.isFile) { "No statement file at ${file.path}." }

    val bound = bindNames(file.readText())
    val wanted = bound.names.toSet()

    val missing = wanted - values.keys
    require(missing.isEmpty()) { "The statement uses ${missing.sorted()}, which no parameter supplies." }

    val unused = values.keys - wanted
    require(unused.isEmpty()) { "${unused.sorted()} were supplied, but the statement uses no such name." }

    return SqlQuery(bound.sql, bound.names.map { values.getValue(it) }, fetchSize, maxRows)
}
