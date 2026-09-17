@file:Suppress("PackageDirectoryMismatch", "SqlResolve")

package carp.dsp.steps.sql

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [QuerySqlStep] over a real driver and a real file. */
class QuerySqlStepTest {

    private companion object {
        val databases = AtomicInteger()
    }

    private val target = SqlConnection("jdbc:h2:mem:query-sql-step-${databases.incrementAndGet()}")
    private val keepAlive: Connection = DriverManager.getConnection(target.url)

    private val output: File =
        File.createTempFile("result", ".csv").apply { delete(); deleteOnExit() }

    init {
        keepAlive.createStatement().use {
            it.execute("CREATE TABLE reading (id INT, label VARCHAR(64))")
            it.execute(
                """
                INSERT INTO reading VALUES
                    (1, 'plain'),
                    (2, 'has,comma'),
                    (3, NULL)
                """.trimIndent()
            )
        }
    }

    @AfterTest
    fun tearDown() = keepAlive.close()

    private fun run(sql: String, maxRows: Int? = null): Long =
        QuerySqlStep().run(target, SqlQuery(sql, maxRows = maxRows), output)

    @Test
    fun `the result is written as CSV, header first`() {
        val rows = run("SELECT id, label FROM reading WHERE id = 1")

        assertEquals(1L, rows)
        assertEquals(
            listOf("ID,LABEL", "1,plain"),
            output.readLines().filter { it.isNotEmpty() },
        )
    }

    @Test
    fun `a cell holding a separator is quoted`() {
        run("SELECT label FROM reading WHERE id = 2")

        assertTrue(output.readText().contains("\"has,comma\""), output.readText())
    }

    @Test
    fun `a SQL NULL is an empty cell`() {
        run("SELECT id, label FROM reading WHERE id = 3")

        assertEquals("3,", output.readLines().last { it.isNotEmpty() })
    }

    // ── Nothing half-written ─────────────────────────────────────────────────

    @Test
    fun `a read that fails leaves no file behind`() {
        assertFailsWith<IllegalStateException> { run("SELECT id FROM reading ORDER BY id", maxRows = 1) }

        assertFalse(output.exists(), "a partial table would be read by the next step as if it were whole")
    }

    @Test
    fun `a read that fails leaves no scratch file behind either`() {
        val directory = output.absoluteFile.parentFile
        val before = directory.list().orEmpty().count { it.endsWith(".partial") }

        assertFailsWith<SqlAccessException> { run("SELECT nonsense FROM nowhere") }

        assertEquals(before, directory.list().orEmpty().count { it.endsWith(".partial") })
    }

    @Test
    fun `an earlier result is replaced, not appended to`() {
        run("SELECT id FROM reading WHERE id = 1")
        run("SELECT id FROM reading WHERE id = 2")

        assertEquals(listOf("ID", "2"), output.readLines().filter { it.isNotEmpty() })
    }
}
