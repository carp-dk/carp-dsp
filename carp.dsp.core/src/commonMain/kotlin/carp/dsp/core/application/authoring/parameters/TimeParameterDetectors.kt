package carp.dsp.core.application.authoring.parameters

import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor

/**
 * Finds time values among one step's arguments.
 *
 * Detection is a chain of these, so recognising a new pattern is a new detector.
 */
fun interface TimeParameterDetector
{
    /** Returns the time values it recognises in [arguments], each value keyed by where it sits. */
    fun detect(arguments: Map<ArgumentRef, String>): List<TimeParameter>
}

/**
 * Recognises a value by its key, such as `--from` or `--param since=...`.
 *
 * Reads a bare number as epoch milliseconds.
 */
class KnownKeyDetector(private val keys: Set<String> = DEFAULT_TIME_KEYS) : TimeParameterDetector
{
    override fun detect(arguments: Map<ArgumentRef, String>): List<TimeParameter> =
        arguments.mapNotNull { (ref, value) ->
            val key = (ref.name ?: ref.flag.trimStart('-')).lowercase()
            if (key !in keys) return@mapNotNull null

            TimeFormat.of(value, allowEpoch = true)?.let { TimeParameter(ref, value, it) }
        }

    companion object
    {
        /** Keys taken to hold a time. */
        val DEFAULT_TIME_KEYS: Set<String> = setOf("from", "to", "start", "end", "since", "until")
    }
}

/** Recognises any value written as an ISO-8601 date-time with an offset, or a date. */
class ValueShapeDetector : TimeParameterDetector
{
    override fun detect(arguments: Map<ArgumentRef, String>): List<TimeParameter> =
        arguments.mapNotNull { (ref, value) -> TimeFormat.of(value)?.let { TimeParameter(ref, value, it) } }
}

/** The detectors used when none are given, strongest first. */
val DEFAULT_TIME_DETECTORS: List<TimeParameterDetector> = listOf(KnownKeyDetector(), ValueShapeDetector())

/**
 * Returns the time values in [workflow]'s step arguments, in step and argument
 * order. When two detectors find the same value, the earlier in [detectors] wins.
 */
fun detectTimeParameters(
    workflow: WorkflowDescriptor,
    detectors: List<TimeParameterDetector> = DEFAULT_TIME_DETECTORS,
): List<TimeParameter> =
    stepArgumentsOf(workflow).flatMap { step ->
        val arguments = step.entries.associate { it.ref to it.value }

        detectors.flatMap { it.detect(arguments) }
            .distinctBy { it.ref }
            .sortedBy { found -> arguments.keys.indexOf(found.ref) }
    }
