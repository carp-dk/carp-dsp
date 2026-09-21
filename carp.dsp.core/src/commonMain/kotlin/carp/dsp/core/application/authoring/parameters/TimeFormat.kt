package carp.dsp.core.application.authoring.parameters

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * How a time value is written in a step argument.
 *
 * A replacement is written in the format the original was found in.
 */
enum class TimeFormat
{
    /** `2026-10-12T10:00:00Z`, or with any offset. */
    ISO_DATE_TIME,

    /** `2026-10-12`, the start of that day in UTC. */
    ISO_DATE,

    /** `1791799200000`: milliseconds since 1970-01-01T00:00:00Z. */
    EPOCH_MILLIS;

    /** Returns [value] as an instant, or `null` when it is not in this format. */
    fun parse(value: String): Instant? = runCatching {
        when (this)
        {
            ISO_DATE_TIME -> Instant.parse(value)
            ISO_DATE -> LocalDate.parse(value).atStartOfDayIn(TimeZone.UTC)
            // 12 to 14 digits spans 2001 to 5138; a shorter number is a count, not a time.
            EPOCH_MILLIS ->
                value.takeIf { it.length in 12..14 && it.all(Char::isDigit) }
                    ?.let { Instant.fromEpochMilliseconds(it.toLong()) }
        }
    }.getOrNull()

    /** Returns [instant] written in this format. */
    fun format(instant: Instant): String = when (this)
    {
        ISO_DATE_TIME -> instant.toString()
        ISO_DATE -> instant.toLocalDateTime(TimeZone.UTC).date.toString()
        EPOCH_MILLIS -> instant.toEpochMilliseconds().toString()
    }

    companion object
    {
        /**
         * Returns the format [value] is written in, or `null` when it is not a time.
         *
         * A number is read as epoch milliseconds only when [allowEpoch] is set,
         * since a bare number is a time only when its key says so.
         */
        fun of(value: String, allowEpoch: Boolean = false): TimeFormat? =
            listOfNotNull(ISO_DATE_TIME, ISO_DATE, EPOCH_MILLIS.takeIf { allowEpoch })
                .firstOrNull { it.parse(value) != null }
    }
}
