@file:JvmName("FetchCarpStudyData")

package carp.dsp.steps.data

import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/** Exit code for arguments that are not a valid configuration. */
const val EXIT_BAD_ARGUMENTS: Int = 2

/** Exit code for a configured run that failed. */
const val EXIT_FAILED: Int = 1

private const val USAGE = """
Usage: fetch-carp-study-data --study-id <uuid> --output <path> [options]

  --study-id <uuid>                  study to read (required)
  --output <path>                    measurement table (required)
  --provenance-output <path>         provenance table; omitted writes none
  --target <deployment-id>[:<role>]  repeatable; omitted reads every deployment
  --targets-file <path>              one target per line, # comments allowed
  --data-type <namespaced type>      repeatable; omitted reads every type
  --from <epoch ms>                  window start, inclusive
  --to <epoch ms>                    window end, exclusive
  --column <name>                    repeatable; omitted writes every column
  --format <csv>                     output format
  --refresh-cache                    re-read rather than serve a previous read
  --services <name>                  services to read through
"""

/**
 * Fetches a study's data and writes it as a table.
 *
 * Returns the paths written, in the order they were written.
 *
 * @throws IllegalArgumentException when [args] are not a valid configuration.
 * @throws IllegalStateException when the study returns no measurements.
 */
suspend fun fetchStudyData(args: List<String>): List<String>
{
    val config = parseStudyDataArgs(args)
    val services = StudyServicesFactory.create(config.services)
    return StudyDataStep.over(services).run(config)
}

/**
 * Entry point for the fetch-study-data step.
 *
 * Writes the paths written to standard output. Exits [EXIT_BAD_ARGUMENTS] for a
 * configuration this step cannot accept, [EXIT_FAILED] for a run that failed.
 */
fun main(args: Array<String>)
{
    val written = try
    {
        runBlocking { fetchStudyData(args.toList()) }
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

    written.forEach(::println)
}
