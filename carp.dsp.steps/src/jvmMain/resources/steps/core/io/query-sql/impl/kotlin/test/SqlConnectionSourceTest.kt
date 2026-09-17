@file:Suppress("PackageDirectoryMismatch")

package carp.dsp.steps.sql

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where a connection comes from, and where it must not come from.
 */
class SqlConnectionSourceTest {

    private fun connectionFile(contents: String): File =
        File.createTempFile("connection", ".properties")
            .apply { deleteOnExit(); writeText(contents.trimIndent()) }

    private fun refused(block: () -> Unit): String =
        assertFailsWith<IllegalArgumentException>(block = block).message.orEmpty()

    // ── The two sources ──────────────────────────────────────────────────────

    @Test
    fun `the environment alone is enough`() {
        val connection = sqlConnectionFrom(
            environment = mapOf(
                SqlConnectionEnvironment.URL to "jdbc:postgresql://host/carp",
                SqlConnectionEnvironment.USER to "reader",
                SqlConnectionEnvironment.PASSWORD to "secret",
            ),
        )

        assertEquals("jdbc:postgresql://host/carp", connection.url)
        assertEquals("reader", connection.user)
        assertEquals("secret", connection.password)
    }

    @Test
    fun `a file alone is enough`() {
        val file = connectionFile(
            """
            url=jdbc:postgresql://host/carp
            user=reader
            password=secret
            """
        )

        val connection = sqlConnectionFrom(file, environment = emptyMap())

        assertEquals("reader", connection.user)
        assertEquals("secret", connection.password)
    }

    @Test
    fun `the environment overrides the file one field at a time`() {
        // The case this exists for: a url committed beside the workflow, the
        // password supplied only at run time.
        val file = connectionFile(
            """
            url=jdbc:postgresql://host/carp
            user=reader
            """
        )

        val connection = sqlConnectionFrom(
            file,
            environment = mapOf(SqlConnectionEnvironment.PASSWORD to "from-the-environment"),
        )

        assertEquals("jdbc:postgresql://host/carp", connection.url)
        assertEquals("reader", connection.user)
        assertEquals("from-the-environment", connection.password)
    }

    @Test
    fun `a connection needs no user, because some databases do not`() {
        val connection = sqlConnectionFrom(
            environment = mapOf(SqlConnectionEnvironment.URL to "jdbc:h2:mem:anything"),
        )

        assertNull(connection.user)
        assertNull(connection.password)
    }

    // ── Refused ──────────────────────────────────────────────────────────────

    @Test
    fun `no url from either source names both of them`() {
        val message = refused { sqlConnectionFrom(environment = emptyMap()) }

        assertTrue(message.contains(SqlConnectionEnvironment.URL), message)
        assertTrue(message.contains(SqlConnectionKeys.URL), message)
    }

    @Test
    fun `a misspelled key is refused, not ignored`() {
        // Silently dropping 'pasword' would surface as an authentication failure
        val file = connectionFile(
            """
            url=jdbc:postgresql://host/carp
            pasword=secret
            """
        )

        val message = refused { sqlConnectionFrom(file, environment = emptyMap()) }

        assertTrue(message.contains("pasword"), message)
    }

    @Test
    fun `a missing connection file is named`() {
        val message = refused { sqlConnectionFrom(File("nowhere/connection.properties")) }

        assertTrue(message.contains("connection.properties"), message)
    }

    // ── What a failure message may repeat ────────────────────────────────────

    @Test
    fun `printing a connection shows neither the password nor the url's query string`() {
        val connection = SqlConnection(
            url = "jdbc:postgresql://host/carp?user=reader&password=in-the-url",
            user = "reader",
            password = "secret",
        )

        val printed = connection.toString()

        assertFalse(printed.contains("secret"), printed)
        assertFalse(printed.contains("in-the-url"), printed)
        assertTrue(printed.contains("jdbc:postgresql://host/carp"), printed)
    }
}
