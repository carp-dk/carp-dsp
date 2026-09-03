package carp.dsp.steps.sql

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SqlStatementsTest {

    private fun refused(sql: String): String =
        assertFailsWith<IllegalArgumentException> { requireSingleReadStatement(sql) }
            .message.orEmpty()

    // ── Allowed ──────────────────────────────────────────────────────────────

    @Test
    fun `a select is allowed, in any case and after any whitespace`() {
        requireSingleReadStatement("SELECT 1")
        requireSingleReadStatement("  \n\t select 1 ")
    }

    @Test
    fun `a common table expression is allowed`() {
        requireSingleReadStatement("WITH t AS (SELECT 1) SELECT * FROM t")
    }

    @Test
    fun `a leading comment does not hide the keyword`() {
        requireSingleReadStatement("-- deployments holding data\nSELECT 1")
        requireSingleReadStatement("/* deployments */ SELECT 1")
        requireSingleReadStatement("/* outer /* nested */ still a comment */ SELECT 1")
    }

    @Test
    fun `a trailing semicolon is not a second statement`() {
        requireSingleReadStatement("SELECT 1;")
        requireSingleReadStatement("SELECT 1 ;  \n")
    }

    @Test
    fun `a semicolon inside a literal is text, not a separator`() {
        requireSingleReadStatement("SELECT * FROM t WHERE label = 'a;b'")
        requireSingleReadStatement("""SELECT * FROM "odd;column" FROM t""")
    }

    @Test
    fun `a comment marker inside a literal is text`() {
        requireSingleReadStatement("SELECT * FROM t WHERE label = '-- not a comment'")
    }

    // ── Refused ──────────────────────────────────────────────────────────────
    @Test
    fun `a statement that writes is refused, whatever it is`() {
        listOf(
            "UPDATE t SET a = 1",
            "DELETE FROM t",
            "INSERT INTO t VALUES (1)",
            "DROP TABLE t",
            "TRUNCATE t",
            "CREATE TABLE t (a INT)",
            "GRANT ALL ON t TO someone",
        ).forEach { sql ->
            assertTrue(refused(sql).contains("SELECT or WITH"), "allowed: $sql")
        }
    }

    @Test
    fun `a second statement after a semicolon is refused`() {
        assertTrue(refused("SELECT 1; DROP TABLE t").contains("Only one statement"))
    }

    @Test
    fun `a write hidden behind a leading comment is still refused`() {
        assertTrue(refused("/* looks harmless */ DELETE FROM t").contains("SELECT or WITH"))
    }

    @Test
    fun `a statement that is only a comment is refused`() {
        assertTrue(refused("-- nothing here\n").contains("empty"))
    }

    @Test
    fun `a dollar quoted body is refused rather than parsed`() {
        assertTrue(refused($$$"SELECT $$a;b$$").contains("Only one statement"))
    }

    // ── The type carries the guard ───────────────────────────────────────────

    @Test
    fun `a query cannot be built around a statement that writes`() {
        assertFailsWith<IllegalArgumentException> { SqlQuery("DELETE FROM t") }
    }
}
