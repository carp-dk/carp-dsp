package carp.dsp.core.application.plan

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame

class CommandRunTest {

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    @Test
    fun `CommandRun round-trips with defaults`() {
        val run = CommandRun(executable = "bash")

        val encoded = json.encodeToString(run)
        val decoded = json.decodeFromString<CommandRun>(encoded)

        assertEquals(run, decoded)
        assertEquals(emptyList(), decoded.safeArgs)
        assertEquals(emptyMap(), decoded.safeEnvVars)
    }

    @Test
    fun `CommandRun round-trips with populated fields`() {
        val stdinBytes = byteArrayOf(0, 1, 2, 3)
        val run = CommandRun(
            executable = "python",
            args = listOf("-c", "print('hi')"),
            cwd = "/tmp",
            envVars = mapOf("A" to "1"),
            stdin = stdinBytes,
            timeoutMs = 2_500
        )

        val encoded = json.encodeToString(run)
        val decoded = json.decodeFromString<CommandRun>(encoded)

        assertEquals(run, decoded)
        assertEquals(decoded.stdin?.contentEquals(stdinBytes), true)
    }

    @Test
    fun `CommandRun validation covers invalid and minimal cases`() {
        CommandRun(executable = "echo")

        assertFailsWith<IllegalArgumentException> { CommandRun(executable = "") }
        assertFailsWith<IllegalArgumentException> { CommandRun(executable = "echo", cwd = "   ") }
        assertFailsWith<IllegalArgumentException> { CommandRun(executable = "echo", timeoutMs = 0) }
        assertFailsWith<IllegalArgumentException> { CommandRun(executable = "echo", timeoutMs = -5) }
    }

    @Test
    fun `CommandRun defensively copies args and envVars`() {
        val args = mutableListOf("a", "b")
        val env = mutableMapOf("X" to "1")

        val run = CommandRun(executable = "echo", args = args, envVars = env)

        assertNotSame(args, run.safeArgs)
        assertNotSame(env, run.safeEnvVars)

        args += "CHANGED"
        env["X"] = "CHANGED"

        assertEquals(listOf("a", "b"), run.safeArgs)
        assertEquals(mapOf("X" to "1"), run.safeEnvVars)
    }

    @Test
    fun `CommandRun equality and hashCode consider all fields`() {
        val base = CommandRun(
            executable = "prog",
            args = listOf("--x"),
            cwd = "/work",
            envVars = mapOf("A" to "1"),
            stdin = byteArrayOf(9, 8, 7),
            timeoutMs = 1_000
        )

        val same = base.copy(stdin = byteArrayOf(9, 8, 7))
        val diffExec = base.copy(executable = "other")
        val diffArgs = base.copy(args = listOf("--different"))
        val diffEnv = base.copy(envVars = mapOf("B" to "2"))
        val diffCwd = base.copy(cwd = "/else")
        val diffTimeout = base.copy(timeoutMs = 2_000)
        val diffStdinNull = base.copy(stdin = null)
        val diffStdinContent = base.copy(stdin = byteArrayOf(1, 2, 3))

        assertEquals(base, base)
        assertEquals(base, same)
        assertEquals(base.hashCode(), same.hashCode())

        assertNotEquals(base, diffExec)
        assertNotEquals(base, diffArgs)
        assertNotEquals(base, diffEnv)
        assertNotEquals(base, diffCwd)
        assertNotEquals(base, diffTimeout)
        assertNotEquals(base, diffStdinNull)
        assertNotEquals(base, diffStdinContent)
        assertNotEquals(base.hashCode(), diffStdinNull.hashCode())
    }

    @Test
    fun `CommandRun companion serializer is available`() {
        assertNotNull(CommandRun.serializer())
    }
}
