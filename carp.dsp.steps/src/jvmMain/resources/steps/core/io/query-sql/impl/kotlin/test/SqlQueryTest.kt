@file:Suppress("PackageDirectoryMismatch", "FunctionName")

package carp.dsp.steps.sql

import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SqlQueryTest {

    @Test
    fun `a blank url is refused`() {
        assertFailsWith<IllegalArgumentException> { SqlConnection("  ") }
    }

    @Test
    fun `a blank statement is refused`() {
        assertFailsWith<IllegalArgumentException> { SqlQuery("  ") }
    }

    @Test
    fun `a fetch size must be positive`() {
        assertFailsWith<IllegalArgumentException> { SqlQuery("SELECT 1", fetchSize = 0) }
    }

    @Test
    fun `a query defaults to no parameters and a batched fetch`() {
        val query = SqlQuery("SELECT 1")

        assertEquals(emptyList(), query.params)
        assertEquals(DEFAULT_FETCH_SIZE, query.fetchSize)
    }

    // ── Reading a statement from a file ──────────────────────────────────────

    private fun statementFile(sql: String): File =
        File.createTempFile("query-sql", ".sql").apply { deleteOnExit(); writeText(sql) }

    @Test
    fun `a statement is read from its file and its names are bound in order`() {
        val file = statementFile("SELECT * FROM t WHERE a = :second AND b = :first")

        val query = sqlQueryFrom(file, mapOf("first" to "1", "second" to "2"))

        assertEquals("SELECT * FROM t WHERE a = ? AND b = ?", query.sql)
        assertEquals(listOf(SqlValue.Text("2"), SqlValue.Text("1")), query.params)
    }

    @Test
    fun `a placeholder with no value is refused, and says which`() {
        val file = statementFile("SELECT * FROM t WHERE a = :studyId")

        val failure = assertFailsWith<IllegalArgumentException> { sqlQueryFrom(file) }

        assertTrue(failure.message.orEmpty().contains("studyId"), failure.message.orEmpty())
    }

    @Test
    fun `a value naming no placeholder is refused, so a typo is not silent`() {
        val file = statementFile("SELECT * FROM t WHERE a = :studyId")

        val failure = assertFailsWith<IllegalArgumentException> {
            sqlQueryFrom(file, mapOf("studyId" to "1", "studID" to "2"))
        }

        assertTrue(failure.message.orEmpty().contains("studID"), failure.message.orEmpty())
    }

    @Test
    fun `a missing file is named, not a stack trace about streams`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            sqlQueryFrom(File("nowhere/at/all.sql"))
        }

        assertTrue(failure.message.orEmpty().contains("all.sql"), failure.message.orEmpty())
    }

    @Test
    fun `a statement file that writes is refused like any other`() {
        val file = statementFile("DELETE FROM t")

        assertFailsWith<IllegalArgumentException> { sqlQueryFrom(file) }
    }

    // ── The time window ──────────────────────────────────────────────────────

    private val from = Instant.parse("2026-10-12T10:00:00Z")
    private val to = Instant.parse("2026-10-12T10:15:00Z")

    @Test
    fun `from and to bind to their placeholders as timestamps`() {
        val file = statementFile("SELECT * FROM t WHERE s = :studyId AND c >= :from AND c < :to")

        val query = sqlQueryFrom(file, mapOf("studyId" to "1"), from = from, to = to)

        assertEquals(
            listOf(SqlValue.Text("1"), SqlValue.Timestamp(from), SqlValue.Timestamp(to)),
            query.params,
        )
    }

    @Test
    fun `an unset bound binds null, so a statement can leave its window open`() {
        val file = statementFile("SELECT * FROM t WHERE (:from IS NULL OR c >= :from)")

        val query = sqlQueryFrom(file)

        assertEquals(listOf(SqlValue.Timestamp(null), SqlValue.Timestamp(null)), query.params)
    }

    @Test
    fun `a bound with no placeholder is refused, so a window is not silently ignored`() {
        val file = statementFile("SELECT * FROM t")

        val failure = assertFailsWith<IllegalArgumentException> { sqlQueryFrom(file, from = from) }

        assertTrue(failure.message.orEmpty().contains("--from"), failure.message.orEmpty())
    }

    @Test
    fun `from and to cannot be given as parameters`() {
        val file = statementFile("SELECT * FROM t WHERE c >= :from")

        val failure = assertFailsWith<IllegalArgumentException> {
            sqlQueryFrom(file, mapOf("from" to "2026-10-12"))
        }

        assertTrue(failure.message.orEmpty().contains("--from and --to"), failure.message.orEmpty())
    }

    @Test
    fun `from must be before to`() {
        val file = statementFile("SELECT * FROM t WHERE c >= :from AND c < :to")

        assertFailsWith<IllegalArgumentException> { sqlQueryFrom(file, from = to, to = from) }
    }
}
