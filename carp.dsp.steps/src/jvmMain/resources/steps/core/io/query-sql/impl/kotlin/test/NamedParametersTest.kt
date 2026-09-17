@file:Suppress("PackageDirectoryMismatch")

package carp.dsp.steps.sql

import kotlin.test.Test
import kotlin.test.assertEquals

class NamedParametersTest {

    @Test
    fun `a name becomes a placeholder and is reported`() {
        val bound = bindNames("SELECT * FROM t WHERE study_id = :studyId")

        assertEquals("SELECT * FROM t WHERE study_id = ?", bound.sql)
        assertEquals(listOf("studyId"), bound.names)
    }

    @Test
    fun `names are reported in the order their placeholders appear`() {
        val bound = bindNames("SELECT * FROM t WHERE a = :second AND b = :first")

        assertEquals("SELECT * FROM t WHERE a = ? AND b = ?", bound.sql)
        assertEquals(listOf("second", "first"), bound.names)
    }

    @Test
    fun `a repeated name is bound once per placeholder`() {
        // JDBC has no notion of one value serving two placeholders, so the name
        // has to appear twice for the value to be sent twice.
        val bound = bindNames("SELECT * FROM t WHERE a = :id OR b = :id")

        assertEquals("SELECT * FROM t WHERE a = ? OR b = ?", bound.sql)
        assertEquals(listOf("id", "id"), bound.names)
    }

    @Test
    fun `underscores and digits are part of a name`() {
        assertEquals(listOf("study_id_2"), bindNames("SELECT :study_id_2").names)
    }

    @Test
    fun `a statement with no names is returned unchanged`() {
        val sql = "SELECT count(*) FROM t"
        val bound = bindNames(sql)

        assertEquals(sql, bound.sql)
        assertEquals(emptyList(), bound.names)
    }

    // ── The colons that are not placeholders ─────────────────────────────────

    @Test
    fun `a cast is left alone`() {
        val sql = "SELECT snapshot::text FROM data_stream_sequence"

        assertEquals(sql, bindNames(sql).sql)
        assertEquals(emptyList(), bindNames(sql).names)
    }

    @Test
    fun `a cast next to a placeholder does not swallow it`() {
        val bound = bindNames("SELECT * FROM t WHERE id::text = :id")

        assertEquals("SELECT * FROM t WHERE id::text = ?", bound.sql)
        assertEquals(listOf("id"), bound.names)
    }

    @Test
    fun `a colon in a literal is text`() {
        val sql = "SELECT * FROM t WHERE label = 'a:b'"

        assertEquals(sql, bindNames(sql).sql)
        assertEquals(emptyList(), bindNames(sql).names)
    }

    @Test
    fun `a colon in a comment is text`() {
        val sql = "-- see :studyId below\nSELECT 1 /* or :other */"

        assertEquals(sql, bindNames(sql).sql)
        assertEquals(emptyList(), bindNames(sql).names)
    }

    @Test
    fun `a colon before a digit is a slice, not a name`() {
        val sql = "SELECT trigger_ids[1:2] FROM t"

        assertEquals(sql, bindNames(sql).sql)
        assertEquals(emptyList(), bindNames(sql).names)
    }
}
