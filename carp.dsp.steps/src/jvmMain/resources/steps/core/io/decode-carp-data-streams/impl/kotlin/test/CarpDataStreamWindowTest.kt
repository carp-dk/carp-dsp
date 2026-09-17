@file:Suppress("PackageDirectoryMismatch")

package carp.dsp.steps.datastream

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CarpDataStreamWindowTest {

    private val deployment = "4f2b8a10-0000-4000-8000-000000000001"

    /** A sequence whose sensor clock already reads epoch microseconds. */
    private fun aligned(vararg atMicroseconds: Long) = snapshot(0, *atMicroseconds)

    /**
     * A sequence whose sensor clock starts at zero, synchronised against a UTC
     * time of [synchronizedOnSeconds] - so sensor time 0 is that wall time.
     */
    private fun snapshot(synchronizedOnSeconds: Int, vararg atMicroseconds: Long): String {
        val stamp = "1970-01-01T00:00:${synchronizedOnSeconds.toString().padStart(2, '0')}Z"
        val measurements = atMicroseconds.joinToString(",") {
            """{"sensorStartTime":$it,"data":{"__type":"dk.cachet.carp.stepcount","steps":1}}"""
        }
        return """{"measurements":[$measurements],"triggerIds":[1],""" +
            """"syncPoint":{"synchronizedOn":"$stamp","sensorTimestampAtSyncPoint":0,""" +
            """"relativeClockSpeed":1.0}}"""
    }

    private fun table(snapshot: String): File {
        val cells = listOf(deployment, "phone", "dk.cachet.carp.stepcount", "0", snapshot)
        val text = CarpDataStreamColumns.ALL.joinToString(",") + "\n" +
            cells.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" } + "\n"
        return File.createTempFile("streams", ".csv").apply { deleteOnExit(); writeText(text) }
    }

    private val measurements = File.createTempFile("measurements", ".csv")
        .apply { delete(); deleteOnExit() }

    private fun decode(snapshot: String, fromMs: Long? = null, toMs: Long? = null): Long =
        DecodeCarpDataStreamsStep().run(table(snapshot), measurements, fromMs = fromMs, toMs = toMs)

    // ── The bounds ───────────────────────────────────────────────────────────

    @Test
    fun `no window keeps everything`() {
        assertEquals(3L, decode(aligned(1_000_000L, 2_000_000L, 3_000_000L)))
    }

    @Test
    fun `the start is inclusive`() {
        assertEquals(2L, decode(aligned(1_000_000L, 2_000_000L), fromMs = 1_000L))
    }

    @Test
    fun `the end is exclusive, as it is through the service`() {
        // The measurement at exactly 2000 ms is not in [1000, 2000).
        assertEquals(1L, decode(aligned(1_000_000L, 2_000_000L), fromMs = 1_000L, toMs = 2_000L))
    }

    @Test
    fun `one end may be left open`() {
        assertEquals(1L, decode(aligned(1_000_000L, 2_000_000L), toMs = 2_000L))
        assertEquals(1L, decode(aligned(1_000_000L, 2_000_000L), fromMs = 2_000L))
    }

    // ── The clock ────────────────────────────────────────────────────────────

    @Test
    fun `a measurement is placed by its synchronised time, not its raw timestamp`() {
        // Sensor time 0, synchronised against 10 s: the measurement happened at
        // 10 000 ms. Comparing the raw 0 would put it at the epoch instead.
        val drifted = snapshot(synchronizedOnSeconds = 10, 0L)

        assertEquals(1L, decode(drifted, fromMs = 5_000L, toMs = 15_000L))
    }

    @Test
    fun `a window around the raw timestamp does not catch a shifted clock`() {
        val drifted = snapshot(synchronizedOnSeconds = 10, 0L)

        // [0, 5000) would hold it if the raw value were compared; it does not.
        assertFailsWith<IllegalStateException> { decode(drifted, fromMs = 0L, toMs = 5_000L) }
    }

    // ── Refused ──────────────────────────────────────────────────────────────

    @Test
    fun `a window matching nothing fails rather than writing a header`() {
        val failure = assertFailsWith<IllegalStateException> {
            decode(aligned(1_000_000L), fromMs = 90_000L, toMs = 99_000L)
        }

        assertTrue(failure.message.orEmpty().contains("end is exclusive"), failure.message.orEmpty())
        assertTrue(!measurements.exists(), "an empty result must not leave a table behind")
    }

    @Test
    fun `a window that ends before it starts is refused`() {
        val message = assertFailsWith<IllegalArgumentException> {
            decode(aligned(1_000_000L), fromMs = 5_000L, toMs = 1_000L)
        }.message.orEmpty()

        assertTrue(message.contains("after it ends"), message)
    }
}
