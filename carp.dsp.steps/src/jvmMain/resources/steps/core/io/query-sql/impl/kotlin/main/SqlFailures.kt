@file:Suppress("PackageDirectoryMismatch")

package carp.dsp.steps.sql

import java.sql.SQLException

/** A database failure, restated in terms of what the caller can change. */
class SqlAccessException(message: String, cause: SQLException) : RuntimeException(message, cause)

// SQLState's class - its first two characters - is the part the standard fixes,
// and what pgjdbc reports. A driver is free to report a code of its own instead
// (H2 answers 90xxx for a broken connection), which falls through to the quoted
// form rather than being guessed at.
private const val CONNECTION = "08"
private const val AUTHORIZATION = "28"
private const val UNKNOWN_DATABASE = "3D"
private const val SYNTAX_OR_ACCESS = "42"

/**
 * Returns [failure] restated for [target], keeping it as the cause.
 *
 * The four classes named are the ones a caller can act on: where it connects,
 * as whom, to what, and what it asked. Anything else is passed through with its
 * SQLState, because guessing would be worse than quoting the driver.
 */
fun sqlFailure(failure: SQLException, target: SqlConnection): SqlAccessException
{
    val state = failure.sqlState ?: failure.nextException?.sqlState

    val message = when (state?.take(2))
    {
        CONNECTION ->
            "Could not reach the database: $target. Check the url, and that the host is " +
                "reachable from wherever this step runs."

        AUTHORIZATION ->
            "The database refused these credentials: $target. Check the user and password, " +
                "and that the role may read."

        UNKNOWN_DATABASE ->
            "The database named in the url does not exist: $target."

        SYNTAX_OR_ACCESS ->
            "The database refused the statement: ${failure.message?.trim()}"

        else ->
            "The database failed (SQLState ${state ?: "none"}): ${failure.message?.trim()}"
    }

    return SqlAccessException(message, failure)
}
