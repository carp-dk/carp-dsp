@file:JvmName("QuerySql")
@file:Suppress("PackageDirectoryMismatch")

package carp.dsp.steps.sql

import kotlin.system.exitProcess

/** Exit code for arguments that are not a valid configuration. */
const val EXIT_BAD_ARGUMENTS: Int = 2

/** Exit code for a configured run that failed. */
const val EXIT_FAILED: Int = 1

private const val USAGE = """
Usage: query-sql --query-file <path> --output <path> [options]

  --query-file <path>        one SELECT or WITH statement (required)
  --output <path>            CSV file to write (required)
  --param <name>=<value>     repeatable; bound to a :name in the statement
  --connection-file <path>   properties file: url, user, password
  --max-rows <n>             fail rather than return more than n rows
  --fetch-size <n>           rows the driver reads at a time
  --allow-empty              treat a result with no rows as a result

The database is named by --connection-file or by CARP_DSP_SQL_URL,
CARP_DSP_SQL_USER and CARP_DSP_SQL_PASSWORD, never by an argument: a
process listing shows arguments to every user on the host.
"""

/**
 * Runs the query described by [args] and writes its result.
 *
 * Returns the number of rows written.
 *
 * @throws IllegalArgumentException when [args] are not a valid configuration.
 * @throws SqlAccessException when the database refuses the read.
 * @throws IllegalStateException when the result breaks the row cap or is empty.
 */
fun querySql(args: List<String>, environment: Map<String, String> = System.getenv()): Long
{
    val config = parseQuerySqlArgs(args)

    val target = sqlConnectionFrom(config.connectionFile, environment)
    val query = sqlQueryFrom(config.queryFile, config.params, config.fetchSize, config.maxRows)
        .copy(requireRows = config.requireRows)

    return QuerySqlStep().run(target, query, config.output)
}

/**
 * Entry point for the query-sql step.
 *
 * Writes the row count to standard output. Exits [EXIT_BAD_ARGUMENTS] for a
 * configuration this step cannot accept, [EXIT_FAILED] for a run that failed.
 */
fun main(args: Array<String>)
{
    val rows = try
    {
        querySql(args.toList())
    }
    // A bad configuration is the caller's to fix, so it earns the usage text; a
    // failed run does not, because the arguments were understood.
    catch (failure: IllegalArgumentException)
    {
        System.err.println(failure.message)
        System.err.println(USAGE.trim())
        exitProcess(EXIT_BAD_ARGUMENTS)
    }
    catch (failure: IllegalStateException)
    {
        System.err.println(failure.message)
        exitProcess(EXIT_FAILED)
    }
    catch (failure: SqlAccessException)
    {
        System.err.println(failure.message)
        exitProcess(EXIT_FAILED)
    }

    println(rows)
}
