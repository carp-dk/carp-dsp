@file:Suppress("PackageDirectoryMismatch")

package carp.dsp.steps.datastream

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DecodeCarpDataStreamsStepTest {

    private val deployment = "4f2b8a10-0000-4000-8000-000000000001"

    private fun snapshot(vararg steps: Pair<Long, Int>) = """
        {"measurements":[${steps.joinToString(",") { (at, count) ->
            """{"sensorStartTime":$at,"data":{"__type":"dk.cachet.carp.stepcount","steps":$count}}"""
        }}],"triggerIds":[1],"syncPoint":{"synchronizedOn":"1970-01-01T00:00:00Z","sensorTimestampAtSyncPoint":0,"relativeClockSpeed":1.0}}
    """.trimIndent()

    /** Written the way `core.io.query-sql` writes it: the JSON cell quoted. */
    private fun table(vararg rows: List<String>): File {
        val text = buildString {
            appendLine(CarpDataStreamColumns.ALL.joinToString(","))
            rows.forEach { cells ->
                appendLine(cells.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" })
            }
        }
        return File.createTempFile("streams", ".csv").apply { deleteOnExit(); writeText(text) }
    }

    private fun output(name: String) =
        File.createTempFile(name, ".csv").apply { delete(); deleteOnExit() }

    private val measurements = output("measurements")
    private val provenance = output("provenance")

    @Test
    fun `a queried table becomes the measurement table`() {
        val input = table(listOf(deployment, "phone", "dk.cachet.carp.stepcount", "0", snapshot(1_000_000L to 1000, 2_000_000L to 2000)))

        val rows = DecodeCarpDataStreamsStep().run(input, measurements)

        assertEquals(2L, rows)
        val lines = measurements.readLines().filter { it.isNotEmpty() }
        assertTrue(lines.first().startsWith("row_id,data_type,"), lines.first())
        assertTrue(lines.first().contains("stepcount.steps"), lines.first())
        assertEquals(3, lines.size, measurements.readText())
    }

    @Test
    fun `the provenance table is written when asked for, and keyed to the same rows`() {
        val input = table(listOf(deployment, "phone", "dk.cachet.carp.stepcount", "0", snapshot(1_000_000L to 1000)))

        DecodeCarpDataStreamsStep().run(input, measurements, provenance)

        val ids = { file: File -> file.readLines().filter { it.isNotEmpty() }.drop(1).map { it.substringBefore(",") } }
        assertEquals(ids(measurements), ids(provenance))
        assertTrue(provenance.readText().contains(deployment), provenance.readText())
    }

    @Test
    fun `no provenance file is written when none is asked for`() {
        val input = table(listOf(deployment, "phone", "dk.cachet.carp.stepcount", "0", snapshot(1_000_000L to 1000)))

        DecodeCarpDataStreamsStep().run(input, measurements)

        assertFalse(provenance.exists())
    }

    @Test
    fun `two streams of a deployment both arrive`() {
        val input = table(
            listOf(deployment, "phone", "dk.cachet.carp.stepcount", "0", snapshot(1_000_000L to 1000)),
            listOf(deployment, "watch", "dk.cachet.carp.stepcount", "0", snapshot(1_500_000L to 500)),
        )

        assertEquals(2L, DecodeCarpDataStreamsStep().run(input, measurements))
    }

    // ── Nothing half-written ─────────────────────────────────────────────────

    @Test
    fun `a snapshot that does not decode leaves no measurement table`() {
        val input = table(listOf(deployment, "phone", "dk.cachet.carp.stepcount", "0", "{ not json"))

        assertFailsWith<IllegalArgumentException> { DecodeCarpDataStreamsStep().run(input, measurements) }

        assertFalse(measurements.exists(), "a partial table would be read as if it were whole")
    }

    @Test
    fun `an empty table fails rather than writing a header`() {
        val input = File.createTempFile("streams", ".csv")
            .apply { deleteOnExit(); writeText(CarpDataStreamColumns.ALL.joinToString(",") + "\n") }

        val failure = assertFailsWith<IllegalStateException> {
            DecodeCarpDataStreamsStep().run(input, measurements)
        }

        assertTrue(failure.message.orEmpty().contains("no rows"), failure.message.orEmpty())
    }

    @Test
    fun `a row missing a column names its position`() {
        val text = "study_deployment_id,snapshot\n\"$deployment\",\"{}\"\n"
        val input = File.createTempFile("streams", ".csv").apply { deleteOnExit(); writeText(text) }

        val message = assertFailsWith<IllegalArgumentException> {
            DecodeCarpDataStreamsStep().run(input, measurements)
        }.message.orEmpty()

        assertTrue(message.contains("Row 1"), message)
        assertTrue(message.contains("data_type"), message)
    }

    @Test
    fun `a missing input is named`() {
        val message = assertFailsWith<IllegalArgumentException> {
            DecodeCarpDataStreamsStep().run(File("nowhere/streams.csv"), measurements)
        }.message.orEmpty()

        assertTrue(message.contains("streams.csv"), message)
    }
}
