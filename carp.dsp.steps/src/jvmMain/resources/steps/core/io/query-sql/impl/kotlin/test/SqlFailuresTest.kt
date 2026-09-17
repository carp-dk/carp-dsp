package carp.dsp.steps.sql

import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a driver's failure is turned into.
 *
 * A caller can change four things - where it connects, as whom, to what, and
 * what it asks - so those four get a sentence and everything else is quoted.
 */
class SqlFailuresTest {

    private val target = SqlConnection(
        url = "jdbc:postgresql://db.internal/carp?password=in-the-url",
        user = "reader",
        password = "secret",
    )

    private fun described(state: String?, message: String = "driver said so"): String =
        sqlFailure(SQLException(message, state), target).message.orEmpty()

    @Test
    fun `a connection failure points at the url and the host`() {
        assertTrue(described("08006").contains("Could not reach"), described("08006"))
    }

    @Test
    fun `an authorisation failure points at the credentials`() {
        assertTrue(described("28P01").contains("refused these credentials"), described("28P01"))
    }

    @Test
    fun `an unknown database is named as such`() {
        assertTrue(described("3D000").contains("does not exist"), described("3D000"))
    }

    @Test
    fun `a statement the database refuses quotes the driver`() {
        val message = described("42P01", "relation \"nowhere\" does not exist")

        assertTrue(message.contains("refused the statement"), message)
        assertTrue(message.contains("relation \"nowhere\""), message)
    }

    @Test
    fun `an unrecognised state is passed through with its code`() {
        val message = described("53200", "out of memory")

        assertTrue(message.contains("53200"), message)
        assertTrue(message.contains("out of memory"), message)
    }

    @Test
    fun `a failure with no state at all is still readable`() {
        assertTrue(described(null).contains("none"), described(null))
    }

    @Test
    fun `the state is read from a chained exception when the thrown one has none`() {
        // pgjdbc reports the state on the next exception often enough that
        // reading only the top one would mislabel a connection failure.
        val thrown = SQLException("wrapper")
        thrown.nextException = SQLException("refused", "08001")

        assertTrue(sqlFailure(thrown, target).message.orEmpty().contains("Could not reach"))
    }

    // ── What a message may repeat ────────────────────────────────────────────

    @Test
    fun `a message names the connection without its secrets`() {
        val message = described("08006")

        assertFalse(message.contains("secret"), message)
        assertFalse(message.contains("in-the-url"), message)
        assertTrue(message.contains("db.internal/carp"), message)
    }

    @Test
    fun `the driver's exception is kept as the cause`() {
        val failure = SQLException("refused", "42601")

        assertEquals(failure, sqlFailure(failure, target).cause)
    }
}
