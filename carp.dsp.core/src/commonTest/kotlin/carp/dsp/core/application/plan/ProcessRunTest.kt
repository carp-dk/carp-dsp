package carp.dsp.core.application.plan

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame

class ProcessRunTest {

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    @Test
    fun `CommandRun serializes and deserializes via ProcessRun`() {
        val run: ProcessRun = CommandRun(
            executable = "python",
            args = listOf("-c", "print('hi')"),
            cwd = "/tmp",
            envVars = mapOf("A" to "B"),
            stdin = byteArrayOf(1, 2, 3),
            timeoutMs = 5_000
        )

        val encoded = json.encodeToString(run)
        val decoded = json.decodeFromString<ProcessRun>(encoded)

        assertEquals(run, decoded)
    }

    @Test
    fun `CommandRun validates fields`() {
        assertFailsWith<IllegalArgumentException> { CommandRun(executable = "") }
        assertFailsWith<IllegalArgumentException> { CommandRun(executable = "echo", cwd = "   ") }
        assertFailsWith<IllegalArgumentException> { CommandRun(executable = "echo", timeoutMs = 0) }
        assertFailsWith<IllegalArgumentException> { CommandRun(executable = "echo", timeoutMs = -1) }
    }

    @Test
    fun `CommandRun defensively copies args and envVars`() {
        val args = mutableListOf("a", "b")
        val env = mutableMapOf("X" to "1")

        val run = CommandRun(executable = "echo", args = args, envVars = env)

        // Ensure we didn't retain the same references
        assertNotSame(args, run.safeArgs)
        assertNotSame(env, run.safeEnvVars)

        // Mutate originals
        args[0] = "CHANGED"
        env["X"] = "CHANGED"

        // Planned run remains unchanged
        assertEquals(listOf("a", "b"), run.safeArgs)
        assertEquals(mapOf("X" to "1"), run.safeEnvVars)
    }

    @Test
    fun `InProcessRun serializes and deserializes via ProcessRun`() {
        val run: ProcessRun = InProcessRun(
            operationId = "operation.example.v1",
            parameters = mapOf("k" to "v")
        )

        val encoded = json.encodeToString(run)
        val decoded = json.decodeFromString<ProcessRun>(encoded)

        assertEquals(run, decoded)
    }

    @Test
    fun `InProcessRun validates fields`() {
        assertFailsWith<IllegalArgumentException> { InProcessRun(operationId = "") }
        assertFailsWith<IllegalArgumentException> { InProcessRun(operationId = "   ") }
    }

    @Test
    fun `InProcessRun defensively copies parameters`() {
        val params = mutableMapOf("k" to "v1")
        val run = InProcessRun(operationId = "operation", parameters = params)

        assertNotSame(params, run.safeParameters)

        params["k"] = "v2"
        assertEquals(mapOf("k" to "v1"), run.safeParameters)
    }
}
