@file:Suppress("PackageDirectoryMismatch")

package carp.dsp.steps.datastream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DecodeCarpDataStreamsArgsTest {

    private val required = listOf("--input", "rows.csv", "--measurements", "out.csv")

    private fun refused(vararg args: String): String =
        assertFailsWith<IllegalArgumentException> { parseDecodeArgs(args.toList()) }.message.orEmpty()

    @Test
    fun `the two required flags are enough`() {
        val config = parseDecodeArgs(required)

        assertEquals("rows.csv", config.input.name)
        assertEquals("out.csv", config.measurements.name)
        assertNull(config.provenance)
        assertNull(config.fromMs)
        assertNull(config.toMs)
    }

    @Test
    fun `a missing input is named`() {
        assertTrue(refused("--measurements", "out.csv").contains("--input"))
    }

    @Test
    fun `a missing measurement table is named`() {
        assertTrue(refused("--input", "rows.csv").contains("--measurements"))
    }

    @Test
    fun `a window is read as epoch milliseconds`() {
        val config = parseDecodeArgs(required + listOf("--from", "1000", "--to", "2000"))

        assertEquals(1000L, config.fromMs)
        assertEquals(2000L, config.toMs)
    }

    @Test
    fun `a window bound that is not a number is refused`() {
        assertTrue(refused(*required.toTypedArray(), "--from", "yesterday").contains("--from"))
    }

    @Test
    fun `a provenance table is optional`() {
        val config = parseDecodeArgs(required + listOf("--provenance", "who.csv"))

        assertEquals("who.csv", config.provenance?.name)
    }

    @Test
    fun `a flag the step does not take is refused, not ignored`() {
        assertTrue(refused(*required.toTypedArray(), "--column", "bpm").contains("--column"))
    }

    @Test
    fun `a flag that takes one value cannot be given twice`() {
        assertTrue(refused(*required.toTypedArray(), "--input", "other.csv").contains("takes one value"))
    }
}
