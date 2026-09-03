@file:JvmName("DecodeCarpDataStreams")
@file:Suppress("PackageDirectoryMismatch")

package carp.dsp.steps.datastream

import kotlin.system.exitProcess

/** Exit code for arguments that are not a valid configuration. */
const val EXIT_BAD_ARGUMENTS: Int = 2

/** Exit code for a configured run that failed. */
const val EXIT_FAILED: Int = 1

private const val USAGE = """
Usage: decode-carp-data-streams --input <path> --measurements <path> [options]

  --input <path>          a queried data-stream table (required)
  --measurements <path>   measurement table to write (required)
  --provenance <path>     provenance table; omitted writes none
  --from <epoch ms>       window start, inclusive
  --to <epoch ms>         window end, exclusive

The input must select study_deployment_id, device_role_name, data_type,
first_sequence_id and snapshot, ordered by stream and first sequence id.
"""

/**
 * Decodes the table described by [args] and writes its measurements.
 *
 * Returns the number of measurement rows written.
 *
 * @throws IllegalArgumentException when [args] are not a valid configuration,
 *   or the input is not a readable table of data-stream rows.
 * @throws IllegalStateException when nothing is left to write.
 */
fun decodeCarpDataStreams(args: List<String>): Long
{
    val config = parseDecodeArgs(args)

    return DecodeCarpDataStreamsStep().run(
        input = config.input,
        measurements = config.measurements,
        provenance = config.provenance,
        fromMs = config.fromMs,
        toMs = config.toMs,
    )
}

/**
 * Entry point for the decode-carp-data-streams step.
 *
 * Writes the row count to standard output. Exits [EXIT_BAD_ARGUMENTS] for a
 * configuration this step cannot accept, [EXIT_FAILED] for a run that failed.
 */
fun main(args: Array<String>)
{
    val rows = try
    {
        decodeCarpDataStreams(args.toList())
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

    println(rows)
}
