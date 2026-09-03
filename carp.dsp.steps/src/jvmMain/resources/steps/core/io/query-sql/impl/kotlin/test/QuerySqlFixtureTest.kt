@file:Suppress("PackageDirectoryMismatch")

package carp.dsp.steps.sql

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The step reproduces the fixture it publishes.
 *
 * A step that reads a database needs the database fixed as well as the input, so
 * the fixture is three files: `seed.sql` builds the table, `query.sql` is the
 * declared input, and `expected.csv` is what the step must write.
 */
class QuerySqlFixtureTest {

    private companion object {
        val databases = AtomicInteger()
    }

    private val target = SqlConnection("jdbc:h2:mem:query-sql-fixture-${databases.incrementAndGet()}")

    /** Held open so the seeded database outlives the step's own connection. */
    private val keepAlive: Connection = DriverManager.getConnection(target.url)

    private fun fixture(name: String): File =
        File(
            checkNotNull(javaClass.classLoader.getResource("steps/core/io/query-sql/reference/$name"))
            { "the step's reference fixture is not on the classpath: $name" }
                .toURI()
        )

    init {
        val statements = fixture("seed.sql").readLines()
            .filterNot { it.trim().startsWith("--") }
            .joinToString("\n")
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        keepAlive.createStatement().use { statement -> statements.forEach { statement.execute(it) } }
    }

    @AfterTest
    fun tearDown() = keepAlive.close()

    /** Line endings are normalised: git may check the fixture out with CRLF. */
    private fun String.lines() = replace("\r\n", "\n")

    @Test
    fun `the step writes the fixture it publishes`() {
        val output = File.createTempFile("fixture", ".csv").apply { delete(); deleteOnExit() }

        // The parameter step.yaml passes by default, so the fixture covers the
        // arguments the step actually ships with.
        val query = sqlQueryFrom(fixture("query.sql"), mapOf("minId" to "1"))
        QuerySqlStep().run(target, query, output)

        assertEquals(fixture("expected.csv").readText().lines(), output.readText().lines())
    }
}
