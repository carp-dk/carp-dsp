package carp.dsp.steps.data

import carp.dsp.core.application.DeploymentTarget
import carp.dsp.core.application.StudyDataRequest
import dk.cachet.carp.common.application.UUID

private const val FLAG_PREFIX = "--"

/**
 * Parses a [StudyDataStepConfig] from command-line arguments.
 *
 * Supported options include study selection, deployment targeting, time-range
 * filtering, output configuration, and export format selection.
 *
 * @throws IllegalArgumentException If the arguments are invalid.
 */
@Suppress("CyclomaticComplexMethod")
fun parseStudyDataArgs(args: List<String>): StudyDataStepConfig
{
    val flags = readFlags(args)

    val studyId = flags.single("--study-id") { UUID.parse(it) }
        ?: throw IllegalArgumentException("--study-id is required.")
    val output = flags.single("--output") { it }
        ?: throw IllegalArgumentException("--output is required.")

    val format = flags.single("--format") { raw ->
        StudyDataFormat.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "--format '$raw' is not a format the table writer supports. " +
                    "Supported: ${StudyDataFormat.entries.joinToString(", ") { it.name.lowercase() }}."
            )
    } ?: StudyDataFormat.CSV

    val services = flags.single("--services") { raw ->
        raw.takeIf { it in StudyServicesFactory.names }
            ?: throw IllegalArgumentException(
                "--services '$raw' is not a services configuration this step knows. " +
                    "Known: ${StudyServicesFactory.names.joinToString(", ")}."
            )
    } ?: StudyServicesFactory.IN_MEMORY

    val request = StudyDataRequest(
        studyId = studyId,
        targets = flags.all("--target").map { parseTarget(it) },
        dataTypes = flags.all("--data-type").toSet(),
        fromMs = flags.single("--from") { it.toLongOrThrow("--from") },
        toMs = flags.single("--to") { it.toLongOrThrow("--to") },
        refreshCache = flags.present("--refresh-cache"),
    )

    return StudyDataStepConfig(
        request = request,
        targetsFile = flags.single("--targets-file") { it },
        format = format,
        outputPath = output,
        provenancePath = flags.single("--provenance-output") { it },
        columns = flags.all("--column").ifEmpty { null },
        services = services,
    )
}

/**
 * Parses a deployment target in the form `<deployment-id>[:<role>]`.
 */
private fun parseTarget(raw: String): DeploymentTarget =
    runCatching { parseTargets(listOf(raw)).single() }
        .getOrElse { throw IllegalArgumentException("--target '$raw' is not a deployment id.", it) }

private fun String.toLongOrThrow(flag: String): Long =
    toLongOrNull() ?: throw IllegalArgumentException("$flag '$this' is not a time in epoch milliseconds.")

/**
 * Stores parsed command-line flags and their values.
 */
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
    "--study-id", "--target", "--targets-file", "--data-type", "--from", "--to",
    "--refresh-cache", "--format", "--column", "--output", "--provenance-output",
    "--services",
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
