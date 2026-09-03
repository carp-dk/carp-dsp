package carp.dsp.steps.sql

import carp.dsp.core.domain.data.CsvTable
import carp.dsp.steps.datastream.carpDataStreamRows
import carp.dsp.steps.datastream.carpTabularDataOf
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import java.io.IOException
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val POSTGRES_IMAGE = "postgres:16-alpine"

private const val USER = "carp"

private const val SKIP_HINT = "Make sure Docker is running, or skip with -PskipIntegration."

/** The statement in `docs/CARP_DATABASE_COUPLING.md`. */
private const val STUDY_DATA = """
    SELECT i.study_deployment_id,
           i.device_role_name,
           i.name_space || '.' || i.name AS data_type,
           s.first_sequence_id,
           s.snapshot
    FROM data_stream_sequence s
             JOIN data_stream_ids i ON i.id = s.data_stream_id
             JOIN recruitment_participant_groups g
                  ON g.group_id = i.study_deployment_id AND g.is_deployed
    WHERE g.study_id = ?
    ORDER BY i.id, s.first_sequence_id
"""

/**
 * Runs `core.io.query-sql` against a Postgres in Docker.
 *
 * The unit tests run on H2, which is not the database this step will meet: it
 * has no `jsonb`, it coerces a text parameter into a numeric column, and it
 * reports failures with codes of its own. Only those three things are asserted
 * here; that the schema matches CARP's is checked against the dev server by
 * hand.
 *
 * Requires Docker to be running. Skip with SKIP_INTEGRATION=true.
 */
class PostgresQueryIntegrationTest
{
    companion object
    {
        private var container: String? = null

        private var url: String = ""

        @BeforeClass
        @JvmStatic
        fun startPostgres()
        {
            if (System.getenv("SKIP_INTEGRATION") == "true") return

            val id = docker(
                "run", "-d", "--rm",
                "-e", "POSTGRES_USER=$USER", "-e", "POSTGRES_PASSWORD=$USER", "-e", "POSTGRES_DB=$USER",
                "-p", "0:5432", POSTGRES_IMAGE,
            )
            container = id

            // `docker port` answers once per address family; either line carries the
            // same host port.
            val port = docker("port", id, "5432/tcp").lineSequence().first().substringAfterLast(':')
            url = "jdbc:postgresql://localhost:$port/$USER"

            await().use { connection ->
                connection.createStatement().use { it.execute(schema()) }
            }
        }

        @AfterClass
        @JvmStatic
        fun stopPostgres()
        {
            container?.let { runCatching { docker("rm", "-f", it) } }
        }

        private fun docker(vararg arguments: String): String
        {
            val command = "docker ${arguments.joinToString(" ")}"

            val process = try
            {
                ProcessBuilder(listOf("docker") + arguments).redirectErrorStream(true).start()
            }
            catch (failure: IOException)
            {
                error("`$command` could not be run: ${failure.message}. $SKIP_HINT")
            }

            val output = process.inputStream.bufferedReader().readText().trim()
            check(process.waitFor() == 0) { "`$command` failed: $output. $SKIP_HINT" }

            return output
        }

        /** Postgres accepts connections a moment after the container is up. */
        private fun await(): Connection
        {
            val deadline = System.currentTimeMillis() + 60_000
            while (true)
            {
                runCatching { return DriverManager.getConnection(url, USER, USER) }
                check(System.currentTimeMillis() < deadline)
                {
                    "Postgres started but never accepted a connection. $SKIP_HINT"
                }
                Thread.sleep(500)
            }
        }

        private fun schema(): String =
            checkNotNull(javaClass.getResource("/postgres/carp-data-streams.sql")) {
                "No schema at /postgres/carp-data-streams.sql."
            }.readText()
    }

    private val study = "11111111-0000-4000-8000-000000000001"

    private val source = JdbcQuerySource()

    private class Rows : SqlRowSink
    {
        var columns: List<String> = emptyList()
        val values = mutableListOf<List<String?>>()

        override fun begin(columns: List<String>)
        {
            this.columns = columns
        }

        override fun row(values: List<String?>)
        {
            this.values += values
        }
    }

    private fun target(user: String = USER, database: String = USER) =
        SqlConnection(url.replace("/$USER", "/$database"), user, USER)

    private fun refused(target: SqlConnection, query: SqlQuery): String =
        assertFailsWith<SqlAccessException> { source.read(target, query, Rows()) }.message.orEmpty()

    @Test
    fun `the documented study query returns rows the decoder can read`()
    {
        assumeTrue("Skipping Postgres integration", System.getenv("SKIP_INTEGRATION") != "true")

        val sink = Rows()
        val rows = source.read(target(), SqlQuery(STUDY_DATA, listOf(study)), sink)

        // Two sequences on the deployed group's stream; the staged group has none.
        assertEquals(2L, rows)

        // jsonb comes back as text with its keys reordered, so nothing may depend
        // on the order they were written in.
        val data = carpTabularDataOf(carpDataStreamRows(CsvTable(sink.columns, sink.values)))
        assertEquals(listOf(1_000_000L, 2_000_000L, 3_000_000L), data.rows.map { it.sensorStartTime })
    }

    @Test
    fun `a parameter is sent as text, so comparing it to a number needs a cast`()
    {
        assumeTrue("Skipping Postgres integration", System.getenv("SKIP_INTEGRATION") != "true")

        // Postgres has no `integer = varchar` and H2 never showed this, because it
        // coerces. The fix belongs in the statement: the step cannot know the column.
        val uncast = SqlQuery("SELECT count(*) FROM data_stream_ids WHERE id = ?", listOf("1"))
        assertContains(refused(target(), uncast), "refused the statement")

        val cast = SqlQuery("SELECT count(*) FROM data_stream_ids WHERE id = CAST(? AS integer)", listOf("1"))
        val sink = Rows()

        source.read(target(), cast, sink)

        val counted: List<List<String?>> = listOf(listOf("1"))
        assertEquals(counted, sink.values)
    }

    @Test
    fun `a real driver's failures land in the class they are restated for`()
    {
        assumeTrue("Skipping Postgres integration", System.getenv("SKIP_INTEGRATION") != "true")

        val query = SqlQuery(STUDY_DATA, listOf(study))

        assertContains(refused(target(user = "nobody"), query), "credentials")
        assertContains(refused(target(database = "no-such-db"), query), "does not exist")
        assertContains(refused(SqlConnection("jdbc:postgresql://localhost:1/$USER", USER, USER), query), "Could not reach")
        assertContains(refused(target(), SqlQuery("SELECT * FROM no_such_table")), "no_such_table")
    }
}
