@file:Suppress("PackageDirectoryMismatch")

package carp.dsp.steps.sql

import java.io.File

private const val FLAG_PREFIX = "--"

/**
 * Everything the step needs, from its arguments.
 *
 * No connection setting appears here: a url, user or password given as an
 * argument would be visible to anyone who can list processes on the host.
 */
data class QuerySqlConfig(
    val queryFile: File,
    val output: File,
    val params: Map<String, String?> = emptyMap(),
    val connectionFile: File? = null,
    val maxRows: Int? = null,
    val fetchSize: Int = DEFAULT_FETCH_SIZE,
    val requireRows: Boolean = true,
)

/**
 * Parses a [QuerySqlConfig] from command-line arguments.
 *
 * @throws IllegalArgumentException when a flag is unknown, given more often than
 *   it takes a value, or carries a value this step cannot read.
 */
fun parseQuerySqlArgs(args: List<String>): QuerySqlConfig
{
    val flags = readFlags(args)

    val queryFile = flags.single("--query-file") { File(it) }
        ?: throw IllegalArgumentException("--query-file is required.")
    val output = flags.single("--output") { File(it) }
        ?: throw IllegalArgumentException("--output is required.")

    return QuerySqlConfig(
        queryFile = queryFile,
        output = output,
        params = flags.all("--param").associate(::parseParameter),
        connectionFile = flags.single("--connection-file") { File(it) },
        maxRows = flags.single("--max-rows") { it.toPositiveIntOrThrow("--max-rows") },
        fetchSize = flags.single("--fetch-size") { it.toPositiveIntOrThrow("--fetch-size") }
            ?: DEFAULT_FETCH_SIZE,
        requireRows = !flags.present("--allow-empty"),
    )
}

/** Splits `name=value`, keeping an `=` in the value. */
private fun parseParameter(raw: String): Pair<String, String?>
{
    val name = raw.substringBefore('=')
    require(name.isNotBlank() && raw.contains('=')) { "--param '$raw' is not name=value." }

    return name to raw.substringAfter('=')
}

private fun String.toPositiveIntOrThrow(flag: String): Int
{
    val value = toIntOrNull()
    require(value != null && value > 0) { "$flag '$this' is not a positive whole number." }

    return value
}

/** Stores parsed command-line flags and their values. */
private class Flags(private val values: Map<String, List<String>>)
{
    fun all(flag: String): List<String> = values[flag].orEmpty()

    fun present(flag: String): Boolean = values.containsKey(flag)

    fun <T> single(flag: String, parse: (String) -> T): T?
    {
        val found = all(flag)
        require(found.size <= 1) { "$flag was given ${found.size} times; it takes one value." }
        val raw = found.firstOrNull() ?: return null

        return runCatching { parse(raw) }.getOrElse { failure ->
            throw IllegalArgumentException(failure.message ?: "$flag '$raw' could not be read.", failure)
        }
    }
}

private val KNOWN_FLAGS = setOf(
    "--query-file", "--output", "--param", "--connection-file",
    "--max-rows", "--fetch-size", "--allow-empty",
)

private fun readFlags(args: List<String>): Flags
{
    val values = mutableMapOf<String, MutableList<String>>()
    var index = 0

    while (index < args.size)
    {
        val token = args[index]
        require(token.startsWith(FLAG_PREFIX)) { "Expected a flag but found '$token'." }
        require(token in KNOWN_FLAGS) { "'$token' is not a flag this step takes." }

        val next = args.getOrNull(index + 1)
        val bucket = values.getOrPut(token) { mutableListOf() }

        if (next == null || next.startsWith(FLAG_PREFIX))
        {
            index += 1 // A switch, or a flag whose value was left off.
        }
        else
        {
            bucket += next
            index += 2
        }
    }

    return Flags(values)
}
