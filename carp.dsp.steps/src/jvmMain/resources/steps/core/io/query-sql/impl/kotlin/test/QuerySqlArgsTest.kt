@file:Suppress("PackageDirectoryMismatch")

package carp.dsp.steps.sql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuerySqlArgsTest {

    private val required = listOf("--query-file", "q.sql", "--output", "out.csv")

    private fun refused(vararg args: String): String =
        assertFailsWith<IllegalArgumentException> { parseQuerySqlArgs(args.toList()) }.message.orEmpty()

    @Test
    fun `the two required flags are enough`() {
        val config = parseQuerySqlArgs(required)

        assertEquals("q.sql", config.queryFile.name)
        assertEquals("out.csv", config.output.name)
        assertNull(config.connectionFile)
        assertNull(config.maxRows)
        assertEquals(DEFAULT_FETCH_SIZE, config.fetchSize)
        assertTrue(config.requireRows)
    }

    @Test
    fun `a missing query file is named`() {
        assertTrue(refused("--output", "out.csv").contains("--query-file"))
    }

    @Test
    fun `a missing output is named`() {
        assertTrue(refused("--query-file", "q.sql").contains("--output"))
    }

    @Test
    fun `parameters are collected by name`() {
        val config = parseQuerySqlArgs(
            required + listOf("--param", "studyId=abc", "--param", "role=phone"),
        )

        assertEquals(mapOf("studyId" to "abc", "role" to "phone"), config.params)
    }

    @Test
    fun `a parameter value may hold an equals sign`() {
        val config = parseQuerySqlArgs(required + listOf("--param", "filter=a=b"))

        assertEquals(mapOf("filter" to "a=b"), config.params)
    }

    @Test
    fun `a parameter value may be empty`() {
        val config = parseQuerySqlArgs(required + listOf("--param", "note="))

        assertEquals(mapOf("note" to ""), config.params)
    }

    @Test
    fun `a parameter without an equals sign is refused`() {
        assertTrue(refused(*required.toTypedArray(), "--param", "studyId").contains("name=value"))
    }

    @Test
    fun `allow-empty is a switch`() {
        assertTrue(!parseQuerySqlArgs(required + "--allow-empty").requireRows)
    }

    @Test
    fun `a row cap must be a positive whole number`() {
        assertTrue(refused(*required.toTypedArray(), "--max-rows", "many").contains("--max-rows"))
        assertTrue(refused(*required.toTypedArray(), "--max-rows", "0").contains("--max-rows"))
    }

    @Test
    fun `a flag the step does not take is refused, not ignored`() {
        // Silently dropping it would run a query configured differently from the
        // way the workflow says it is.
        assertTrue(refused(*required.toTypedArray(), "--password", "secret").contains("--password"))
    }

    @Test
    fun `a flag that takes one value cannot be given twice`() {
        assertTrue(
            refused(*required.toTypedArray(), "--output", "other.csv").contains("takes one value"),
        )
    }

    @Test
    fun `no connection setting is a flag at all`() {
        // The one property worth a test of its own: these must come from a file
        // or the environment, never from something a process listing shows.
        listOf("--url", "--user", "--password").forEach { flag ->
            assertTrue(refused(*required.toTypedArray(), flag, "x").contains(flag), flag)
        }
    }
}
