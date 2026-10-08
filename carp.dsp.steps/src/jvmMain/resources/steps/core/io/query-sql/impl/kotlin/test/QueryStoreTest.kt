@file:Suppress("PackageDirectoryMismatch", "SqlResolve")

package carp.dsp.steps.sql

import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `--incremental` over a real driver: rows arrive between runs, and only they are fetched. */
class QueryStoreTest {

    private companion object {
        val databases = AtomicInteger()
    }

    private val url = "jdbc:h2:mem:query-store-${databases.incrementAndGet()}"
    private val keepAlive: Connection = DriverManager.getConnection(url)
    private val dir: File = Files.createTempDirectory("query-store").toFile()
    private val statement = File(dir, "arrivals.sql").apply {
        writeText(
            "SELECT id, label FROM arrival " +
                "WHERE (CAST(:from AS TIMESTAMP) IS NULL OR created_at >= :from) AND created_at < :to " +
                "ORDER BY id"
        )
    }
    private val output = File(dir, "out/rows.csv")
    private val environment = mapOf(
        "CARP_DSP_SQL_URL" to url,
        QUERY_STORE_VARIABLE to File(dir, "store").path,
    )

    init {
        keepAlive.createStatement().use {
            it.execute("CREATE TABLE arrival (id INT, label VARCHAR(64), created_at TIMESTAMP)")
        }
    }

    @AfterTest
    fun tearDown() = keepAlive.close()

    private fun arrive(id: Int, at: String) =
        keepAlive.createStatement().use { it.execute("INSERT INTO arrival VALUES ($id, 'r$id', TIMESTAMP '$at')") }

    private fun run(to: String, vararg extra: String): Long = querySql(
        listOf(
            "--query-file", statement.path, "--output", output.path,
            "--from", "2026-10-01T00:00:00Z", "--to", to, "--incremental", *extra,
        ),
        environment,
    )

    private fun ids(): List<String> = output.readLines().filter { it.isNotEmpty() }.drop(1).map { it.substringBefore(",") }

    private fun stored(): File = File(dir, "store").listFiles { f -> f.extension == "csv" }!!.single()

    @Test
    fun `a later run fetches only what arrived since, and writes everything`() {
        arrive(1, "2026-10-12 08:00:00")
        run("2026-10-12T09:00:00Z")

        arrive(2, "2026-10-12 09:30:00")
        // An earlier row changed after its window closed is not fetched again.
        keepAlive.createStatement().use { it.execute("UPDATE arrival SET label = 'changed' WHERE id = 1") }
        val rows = run("2026-10-12T10:00:00Z")

        assertEquals(2L, rows)
        assertEquals(listOf("1", "2"), ids())
        assertTrue(output.readText().contains("r1"), output.readText())
    }

    @Test
    fun `nothing new leaves the result exactly as it was`() {
        arrive(1, "2026-10-12 08:00:00")
        run("2026-10-12T09:00:00Z")
        val before = output.readBytes()

        run("2026-10-12T09:15:00Z")

        assertTrue(before.contentEquals(output.readBytes()))
    }

    @Test
    fun `the same window end reads nothing`() {
        arrive(1, "2026-10-12 08:00:00")
        run("2026-10-12T09:00:00Z")
        keepAlive.createStatement().use { it.execute("DROP TABLE arrival") }

        assertEquals(1L, run("2026-10-12T09:00:00Z"))
    }

    @Test
    fun `an earlier window end starts again with one full read`() {
        arrive(1, "2026-10-12 08:00:00")
        arrive(2, "2026-10-12 09:30:00")
        run("2026-10-12T10:00:00Z")

        assertEquals(1L, run("2026-10-12T09:00:00Z"))
        assertEquals(listOf("1"), ids())
    }

    @Test
    fun `an earlier result is not changed by a later run`() {
        arrive(1, "2026-10-12 08:00:00")
        run("2026-10-12T09:00:00Z")
        val first = File(dir, "first.csv").also { output.copyTo(it); output.renameTo(File(dir, "kept.csv")) }

        arrive(2, "2026-10-12 09:30:00")
        run("2026-10-12T10:00:00Z")

        assertEquals(first.readText(), File(dir, "kept.csv").readText())
        assertEquals(listOf("1", "2"), ids())
    }

    @Test
    fun `the store keeps one header`() {
        arrive(1, "2026-10-12 08:00:00")
        run("2026-10-12T09:00:00Z")
        arrive(2, "2026-10-12 09:30:00")
        run("2026-10-12T10:00:00Z")

        assertEquals(1, stored().readLines().count { it == "ID,LABEL" })
    }

    @Test
    fun `runs at the same time take turns and fetch nothing twice`() {
        arrive(1, "2026-10-12 08:00:00")
        run("2026-10-12T09:00:00Z")
        arrive(2, "2026-10-12 09:30:00")
        arrive(3, "2026-10-12 10:30:00")

        val pool = Executors.newFixedThreadPool(2)
        val ends = listOf("2026-10-12T10:00:00Z", "2026-10-12T11:00:00Z")
        ends.map { end ->
            pool.submit<Long> {
                querySql(
                    listOf(
                        "--query-file", statement.path, "--output", File(dir, "out/${end.take(13)}.csv").path,
                        "--from", "2026-10-01T00:00:00Z", "--to", end, "--incremental",
                    ),
                    environment,
                )
            }
        }
            .forEach { it.get() }
        pool.shutdown()
        pool.awaitTermination(1, TimeUnit.MINUTES)

        // Whichever went first, the store now holds each row once.
        run("2026-10-12T11:00:00Z")
        assertEquals(listOf("1", "2", "3"), ids())
    }

    @Test
    fun `an empty store fails unless empty results are allowed`() {
        assertFailsWith<IllegalStateException> { run("2026-10-12T09:00:00Z") }

        assertEquals(0L, run("2026-10-12T09:00:00Z", "--allow-empty"))
    }

    @Test
    fun `incremental needs a window end`() {
        assertFailsWith<IllegalArgumentException> {
            querySql(
                listOf("--query-file", statement.path, "--output", output.path, "--incremental"),
                environment,
            )
        }
    }
}
