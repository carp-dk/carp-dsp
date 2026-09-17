@file:Suppress("PackageDirectoryMismatch")

package carp.dsp.steps.datastream

import java.io.File

private const val FLAG_PREFIX = "--"

/** Everything the step needs, from its arguments. */
data class DecodeCarpDataStreamsConfig(
    val input: File,
    val measurements: File,
    val provenance: File? = null,
    val fromMs: Long? = null,
    val toMs: Long? = null,
)

/**
 * Parses a [DecodeCarpDataStreamsConfig] from command-line arguments.
 *
 * @throws IllegalArgumentException when a flag is unknown, given more than
 *   once, or carries a value this step cannot read.
 */
fun parseDecodeArgs(args: List<String>): DecodeCarpDataStreamsConfig
{
    val flags = readFlags(args)

    val input = flags.single("--input") { File(it) }
        ?: throw IllegalArgumentException("--input is required.")
    val measurements = flags.single("--measurements") { File(it) }
        ?: throw IllegalArgumentException("--measurements is required.")

    return DecodeCarpDataStreamsConfig(
        input = input,
        measurements = measurements,
        provenance = flags.single("--provenance") { File(it) },
        fromMs = flags.single("--from") { it.toEpochMillisecondsOrThrow("--from") },
        toMs = flags.single("--to") { it.toEpochMillisecondsOrThrow("--to") },
    )
}

private fun String.toEpochMillisecondsOrThrow(flag: String): Long =
    toLongOrNull() ?: throw IllegalArgumentException("$flag '$this' is not a time in epoch milliseconds.")

/** Stores parsed command-line flags and their values. */
private class Flags(private val values: Map<String, List<String>>)
{
    fun all(flag: String): List<String> = values[flag].orEmpty()

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

private val KNOWN_FLAGS = setOf("--input", "--measurements", "--provenance", "--from", "--to")

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
            index += 1 // A flag whose value was left off.
        }
        else
        {
            bucket += next
            index += 2
        }
    }

    return Flags(values)
}
