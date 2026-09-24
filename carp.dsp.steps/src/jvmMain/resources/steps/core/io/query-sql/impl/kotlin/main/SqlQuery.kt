@file:Suppress("PackageDirectoryMismatch")

package carp.dsp.steps.sql

import java.io.File
import java.time.Instant

/** Rows read before the driver is asked for more. */
const val DEFAULT_FETCH_SIZE: Int = 1000

/** Placeholder names bound from `--from` and `--to` rather than from `--param`. */
val WINDOW_NAMES: Set<String> = setOf("from", "to")

/** A value for one `?` placeholder. */
sealed interface SqlValue
{
    /** Bound as a string, so the database does the conversion. */
    data class Text(val value: String?) : SqlValue

    /** Bound as a timestamp in UTC; `null` binds a typed SQL NULL. */
    data class Timestamp(val value: Instant?) : SqlValue
}

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
 * @property params Values for the statement's `?` placeholders, in order.
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
    val params: List<SqlValue> = emptyList(),
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
 * placeholders and [from] and [to] bound to `:from` and `:to`.
 *
 * Every placeholder needs a value and every value needs a placeholder: a name
 * on one side and not the other is a mistake worth reporting rather than a
 * parameter that quietly does nothing. `:from` and `:to` are the exception on
 * one side - an unset bound binds NULL, so a statement can leave its window open.
 *
 * @throws IllegalArgumentException when [file] is not a file, a placeholder has
 *   no value, a value names no placeholder, [values] names `from` or `to`, a set
 *   bound has no placeholder, [from] is not before [to], or the statement is not
 *   a single read.
 */
fun sqlQueryFrom(
    file: File,
    values: Map<String, String?> = emptyMap(),
    fetchSize: Int = DEFAULT_FETCH_SIZE,
    maxRows: Int? = null,
    from: Instant? = null,
    to: Instant? = null,
): SqlQuery
{
    require(file.isFile) { "No statement file at ${file.path}." }

    val reserved = values.keys intersect WINDOW_NAMES
    require(reserved.isEmpty()) { "${reserved.sorted()} are set with --from and --to, not --param." }
    require(from == null || to == null || from < to) { "--from ($from) must be before --to ($to)." }

    val bound = bindNames(file.readText())
    val wanted = bound.names.toSet()

    val window = mapOf("from" to from, "to" to to)
    val unplaced = window.filterValues { it != null }.keys - wanted
    require(unplaced.isEmpty())
    {
        unplaced.sorted().joinToString { "--$it" } + " was given, but the statement has no " +
            unplaced.sorted().joinToString { ":$it" } + " placeholder."
    }

    val missing = wanted - values.keys - WINDOW_NAMES
    require(missing.isEmpty()) { "The statement uses ${missing.sorted()}, which no parameter supplies." }

    val unused = values.keys - wanted
    require(unused.isEmpty()) { "${unused.sorted()} were supplied, but the statement uses no such name." }

    val params = bound.names.map { name ->
        if (name in WINDOW_NAMES) SqlValue.Timestamp(window.getValue(name)) else SqlValue.Text(values.getValue(name))
    }

    return SqlQuery(bound.sql, params, fetchSize, maxRows)
}
