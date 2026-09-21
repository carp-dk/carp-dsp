package carp.dsp.core.application.authoring.parameters

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class TimeFormatTest
{
    private val tenAm = Instant.parse("2026-10-12T10:00:00Z")

    @Test
    fun `each format is recognised by its shape`()
    {
        assertEquals(TimeFormat.ISO_DATE_TIME, TimeFormat.of("2026-10-12T12:00:00+02:00"))
        assertEquals(TimeFormat.ISO_DATE, TimeFormat.of("2026-10-12"))
        assertEquals(TimeFormat.EPOCH_MILLIS, TimeFormat.of("1791799200000", allowEpoch = true))
    }

    @Test
    fun `a number is a time only when its key allows it`()
    {
        assertNull(TimeFormat.of("1791799200000"))
        assertNull(TimeFormat.of("42", allowEpoch = true))
    }

    @Test
    fun `something date-like that does not parse is not a time`()
    {
        assertNull(TimeFormat.of("2026-13-45"))
        assertNull(TimeFormat.of("phone"))
    }

    @Test
    fun `a date-time without an offset is not a time, since its zone would be a guess`()
    {
        assertNull(TimeFormat.of("2026-10-12T10:00:00"))
    }

    @Test
    fun `a value is written back in the format it was found in`()
    {
        assertEquals("2026-10-12T10:00:00Z", TimeFormat.ISO_DATE_TIME.format(tenAm))
        assertEquals("2026-10-12", TimeFormat.ISO_DATE.format(tenAm))
        assertEquals(tenAm.toEpochMilliseconds().toString(), TimeFormat.EPOCH_MILLIS.format(tenAm))
    }
}
