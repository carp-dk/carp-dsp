package carp.dsp.core.domain.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CsvTest {

    @Test
    fun `the first row is the header`() {
        val table = Csv.read("a,b\n1,2\n")

        assertEquals(listOf("a", "b"), table.columns)
        assertEquals(1, table.rows.size)
    }

    @Test
    fun `a quoted cell may hold a separator`() {
        val table = Csv.read("a,b\n\"x,y\",2\n")

        assertEquals(listOf("x,y", "2"), table.rows.single())
    }

    @Test
    fun `a doubled quote is one quote`() {
        val table = Csv.read("a\n\"he said \"\"no\"\"\"\n")

        assertEquals("""he said "no"""", table.rows.single().single())
    }

    @Test
    fun `a quoted cell may hold a newline`() {
        val table = Csv.read("a,b\n\"line\nbreak\",2\n")

        assertEquals(listOf("line\nbreak", "2"), table.rows.single())
    }

    @Test
    fun `a JSON snapshot survives intact`() {
        val snapshot = """{"measurements":[{"sensorStartTime":1,"data":{"__type":"x","v":"a,b"}}]}"""
        val escaped = snapshot.replace("\"", "\"\"")

        val table = Csv.read("snapshot\n\"$escaped\"\n")

        assertEquals(snapshot, table.rows.single().single())
    }

    @Test
    fun `an empty cell is null`() {
        val table = Csv.read("a,b,c\n1,,3\n")

        assertNull(table.rows.single()[1])
    }

    @Test
    fun `a trailing cell is kept`() {
        assertEquals(listOf("1", null), Csv.read("a,b\n1,\n").rows.single())
    }

    @Test
    fun `a final row without a newline is kept`() {
        assertEquals(listOf("1", "2"), Csv.read("a,b\n1,2").rows.single())
    }

    @Test
    fun `carriage returns are not part of a value`() {
        assertEquals(listOf("1", "2"), Csv.read("a,b\r\n1,2\r\n").rows.single())
    }

    @Test
    fun `a table with no rows still has its header`() {
        val table = Csv.read("a,b\n")

        assertEquals(listOf("a", "b"), table.columns)
        assertTrue(table.rows.isEmpty())
    }

    // ── Refused ──────────────────────────────────────────────────────────────

    @Test
    fun `an empty table is refused`() {
        assertFailsWith<IllegalArgumentException> { Csv.read("") }
    }

    @Test
    fun `a ragged row is refused, and named`() {
        val message = assertFailsWith<IllegalArgumentException> { Csv.read("a,b\n1,2\n3\n") }
            .message.orEmpty()

        assertTrue(message.contains("Row 2"), message)
    }

    // ── Writing, which has to be the inverse ─────────────────────────────────

    @Test
    fun `what escape writes, read reads back`() {
        val awkward = listOf("""plain""", """has,comma""", """has"quote""", "has\nnewline", "")

        val table = Csv.read(Csv.table(listOf("a", "b", "c", "d", "e"), listOf(awkward)))

        // The empty cell is the one round trip CSV cannot make: it comes back null.
        assertEquals(awkward.dropLast(1), table.rows.single().dropLast(1).map { it.orEmpty() })
        assertNull(table.rows.single().last())
    }

    @Test
    fun `a table ends every line`() {
        assertTrue(Csv.table(listOf("a"), listOf(listOf("1"))).endsWith("\n"))
    }
}
