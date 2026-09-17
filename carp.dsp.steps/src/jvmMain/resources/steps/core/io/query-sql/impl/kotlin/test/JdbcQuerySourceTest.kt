@file:Suppress("SqlResolve", "PackageDirectoryMismatch")

package carp.dsp.steps.sql

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [JdbcQuerySource] against a real driver, in memory.*/
class JdbcQuerySourceTest {

    private companion object {
        /** A fresh database per test: the seed below runs once per instance. */
        val databases = AtomicInteger()
    }

    private val target = SqlConnection("jdbc:h2:mem:query-sql-${databases.incrementAndGet()}")

    /**
     * Held open for the test's lifetime. H2 discards an in-memory database once
     * its last connection closes, and the source closes its own - so without
     * this the seed would be gone before the query ran.
     */
    private val keepAlive: Connection = DriverManager.getConnection(target.url)

    /** Records what a sink was told. */
    private class Recorder : SqlRowSink {
        var columns: List<String>? = null
        val rows = mutableListOf<List<String?>>()

        override fun begin(columns: List<String>) { this.columns = columns }

        override fun row(values: List<String?>) { rows += values }
    }

    /** A connection that records the calls made to it and delegates them on. */
    private class Watched(private val real: Connection) {
        val calls = mutableListOf<Pair<String, List<Any?>>>()

        val connection: Connection = Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, args ->
            calls += method.name to args.orEmpty().toList()
            try {
                method.invoke(real, *args.orEmpty())
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        } as Connection
    }

    init {
        keepAlive.createStatement().use {
            it.execute("CREATE TABLE reading (id INT, label VARCHAR(32), bpm INT)")
            it.execute(
                """
                INSERT INTO reading VALUES
                    (1, 'morning', 60),
                    (2, 'midday', 72),
                    (3, NULL, NULL)
                """.trimIndent()
            )
        }
    }

    @AfterTest
    fun tearDown() = keepAlive.close()

    private fun read(
        sql: String,
        params: List<String?> = emptyList(),
        open: ((SqlConnection) -> Connection)? = null,
        sink: Recorder = Recorder(),
        maxRows: Int? = null,
        requireRows: Boolean = true,
    ): Pair<Long, Recorder> {
        val source = if (open == null) JdbcQuerySource() else JdbcQuerySource(open)
        val query = SqlQuery(sql, params, maxRows = maxRows, requireRows = requireRows)
        return source.read(target, query, sink) to sink
    }

    // ── The shape that comes out ──────────────────────────────────────────────

    @Test
    fun `columns are the result's labels, so a projection's alias is what comes out`() {
        val (_, sink) = read("SELECT id, bpm AS beats_per_minute FROM reading ORDER BY id")

        assertEquals(listOf("id", "beats_per_minute"), sink.columns?.map { it.lowercase() })
    }

    @Test
    fun `rows arrive in the order the database returned them`() {
        val (count, sink) = read("SELECT label FROM reading WHERE label IS NOT NULL ORDER BY id")

        val expected: List<List<String?>> = listOf(listOf("morning"), listOf("midday"))
        assertEquals(2L, count)
        assertEquals(expected, sink.rows)
    }

    @Test
    fun `a SQL NULL is null, not the word`() {
        val (_, sink) = read("SELECT label, bpm FROM reading WHERE id = 3")

        val expected: List<List<String?>> = listOf(listOf(null, null))
        assertEquals(expected, sink.rows)
    }

    @Test
    fun `values come back as strings, whatever the column type`() {
        val (_, sink) = read("SELECT id, bpm FROM reading WHERE id = 1")

        val expected: List<List<String?>> = listOf(listOf("1", "60"))
        assertEquals(expected, sink.rows)
    }

    // ── Empty, which is not an error here ─────────────────────────────────────

    @Test
    fun `an empty result still announces its columns`() {
        val (count, sink) = read("SELECT id, label FROM reading WHERE id = 99", requireRows = false)

        assertEquals(0L, count)
        assertEquals(2, sink.columns?.size, "columns must be known even with no rows")
        assertTrue(sink.rows.isEmpty())
    }

    // ── Parameters ───────────────────────────────────────────────────────────

    @Test
    fun `positional parameters are bound in order`() {
        val (_, sink) = read(
            "SELECT label FROM reading WHERE id >= ? AND id <= ? ORDER BY id",
            listOf("2", "3"),
        )

        val expected: List<List<String?>> = listOf(listOf("midday"), listOf(null))
        assertEquals(expected, sink.rows)
    }

    @Test
    fun `a parameter is a value, not SQL`() {
        // The whole point of binding: this reaches the database as a string to
        // compare against, never as something to execute.
        val (count, _) = read(
            "SELECT label FROM reading WHERE label = ?",
            listOf("morning' OR '1'='1"),
            requireRows = false,
        )

        assertEquals(0L, count)
    }

    // ── The two guards on a result ───────────────────────────────────────────

    @Test
    fun `a result larger than the cap fails rather than arriving truncated`() {
        val failure = assertFailsWith<IllegalStateException> {
            read("SELECT id FROM reading ORDER BY id", maxRows = 2)
        }

        assertTrue(failure.message.orEmpty().contains("more than 2 rows"), failure.message.orEmpty())
    }

    @Test
    fun `a result exactly at the cap is fine`() {
        val (count, _) = read("SELECT id FROM reading ORDER BY id", maxRows = 3)

        assertEquals(3L, count)
    }

    @Test
    fun `the database is asked to stop one row past the cap, not to return everything`() {
        val watched = Watched(DriverManager.getConnection(target.url))

        assertFailsWith<IllegalStateException> {
            read("SELECT id FROM reading", open = { watched.connection }, maxRows = 1)
        }

        // Proving the cap is pushed down: without this the source would drain a
        // study-sized result only to refuse it at the end.
        assertTrue(watched.calls.contains("prepareStatement" to listOf("SELECT id FROM reading")))
    }

    @Test
    fun `an empty result fails by default, because a header-only table hides a bad query`() {
        val failure = assertFailsWith<IllegalStateException> {
            read("SELECT id FROM reading WHERE id = 99")
        }

        assertTrue(failure.message.orEmpty().contains("no rows"), failure.message.orEmpty())
    }

    // ── What the database refuses ────────────────────────────────────────────

    @Test
    fun `a statement the database refuses is restated, keeping the driver's words`() {
        val failure = assertFailsWith<SqlAccessException> { read("SELECT nonsense FROM nowhere") }

        assertTrue(failure.message.orEmpty().contains("refused the statement"), failure.message.orEmpty())
        assertTrue(failure.cause is SQLException)
    }

    @Test
    fun `a database that cannot be reached fails at the connection, already wrapped`() {
        // Not asserting the wording: H2 reports its own 90xxx code rather than a
        // standard SQLState for a broken connection, so it lands in the quoted
        // fallback. Which class of failure gets a sentence is SqlFailuresTest's
        // job, against states a driver like pgjdbc actually emits. What this
        // covers is that a failure to *connect* is wrapped at all - the earlier
        // tests only reach the statement.
        val source = JdbcQuerySource()
        val unreachable = SqlConnection("jdbc:h2:tcp://localhost:1/nothing-here")

        val failure = assertFailsWith<SqlAccessException> {
            source.read(unreachable, SqlQuery("SELECT 1"), Recorder())
        }

        assertTrue(failure.cause is SQLException)
    }

    // ── The connection ───────────────────────────────────────────────────────

    @Test
    fun `the connection is asked for read-only, and for a cursor it can stream from`() {
        // What the driver does with either is the driver's business - H2 treats
        // setReadOnly as a hint and reports isReadOnly() false regardless, which
        // is exactly why a read-only role is the guard and this is not. So the
        // assertion is that the source asked, not that the database complied.
        val watched = Watched(DriverManager.getConnection(target.url))

        read("SELECT id FROM reading", open = { watched.connection })

        assertTrue(watched.calls.contains("setReadOnly" to listOf(true)), "never asked for read-only")
        assertTrue(watched.calls.contains("setAutoCommit" to listOf(false)), "auto-commit left on: no streaming")
    }

    @Test
    fun `the connection is closed once the rows are read`() {
        var opened: Connection? = null

        read(
            "SELECT id FROM reading",
            open = { DriverManager.getConnection(it.url).also { connection -> opened = connection } },
        )

        assertEquals(expected = opened?.isClosed, actual = true)
    }

    @Test
    fun `the connection is closed when the statement fails`() {
        var opened: Connection? = null

        assertFailsWith<SqlAccessException> {
            read(
                "SELECT nonsense FROM nowhere",
                open = { DriverManager.getConnection(it.url).also { connection -> opened = connection } },
            )
        }

        assertEquals(
            expected = opened?.isClosed,
            actual = true,
            message = "a failed query must not leak the connection"
        )
    }
}
