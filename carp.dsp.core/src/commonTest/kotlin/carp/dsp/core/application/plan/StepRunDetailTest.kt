package carp.dsp.core.application.plan

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StepRunDetailTest {

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }

    @Test
    fun `CommandDetail validates inputs and defaults`() {
        val detail = CommandDetail(executable = "bash", exitCode = 0)

        assertEquals("bash", detail.executable)
        assertEquals(emptyList(), detail.args)
        assertEquals(0, detail.exitCode)
        assertEquals(false, detail.timedOut)
        assertNull(detail.stdout)
        assertNull(detail.stderr)

        assertFailsWith<IllegalArgumentException> { CommandDetail(executable = "", exitCode = 0) }
        assertFailsWith<IllegalArgumentException> { CommandDetail(executable = "bash", exitCode = -1) }
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `CommandDetail decode uses defaults when fields are missing`() {
        val json = Json {
            encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
        }

        // Only required fields present
        val input = """{"type":"CommandDetail","executable":"bash","exitCode":0}"""
        val decoded = json.decodeFromString<StepRunDetail>(input)

        val d = decoded as CommandDetail
        assertEquals(emptyList(), d.args)
        assertEquals(false, d.timedOut)
        assertNull(d.stdout)
        assertNull(d.stderr)
    }


    @Test
    fun `CommandDetail equality and hashCode consider all fields`() {
        val base = CommandDetail(
            executable = "node",
            args = listOf("-e", "console.log('x')"),
            exitCode = 1,
            timedOut = true,
            stdout = "out",
            stderr = "err"
        )

        val same = base.copy(args = listOf("-e", "console.log('x')"))
        val diffExec = base.copy(executable = "python")
        val diffArgs = base.copy(args = listOf("--other"))
        val diffExit = base.copy(exitCode = 2)
        val diffTimeout = base.copy(timedOut = false)
        val diffStdout = base.copy(stdout = "other")
        val diffStderr = base.copy(stderr = "other")

        assertEquals(base, same)
        assertEquals(base.hashCode(), same.hashCode())
        assertNotEquals(base, diffExec)
        assertNotEquals(base, diffArgs)
        assertNotEquals(base, diffExit)
        assertNotEquals(base, diffTimeout)
        assertNotEquals(base, diffStdout)
        assertNotEquals(base, diffStderr)
    }

    @Test
    fun `CommandDetail round-trips through StepRunDetail serializer`() {
        val detail: StepRunDetail = CommandDetail(
            executable = "bash",
            args = listOf("-lc", "echo hi"),
            exitCode = 0,
            timedOut = false,
            stdout = "hi",
            stderr = null
        )

        val encoded = json.encodeToString(detail)
        val decoded = json.decodeFromString<StepRunDetail>(encoded)

        val restored = assertIs<CommandDetail>(decoded)
        assertEquals(detail, restored)
        assertTrue(encoded.contains("\"type\":\"CommandDetail\""))
    }

    @Test
    fun `CommandDetail companion serializer is available`() {
        assertNotNull(CommandDetail.serializer())
    }

    @Test
    fun `InProcessDetail validates inputs`() {
        val detail = InProcessDetail(operationId = "op-1")

        assertEquals("op-1", detail.operationId)
        assertNull(detail.note)

        assertFailsWith<IllegalArgumentException> { InProcessDetail(operationId = "") }
        assertFailsWith<IllegalArgumentException> { InProcessDetail(operationId = "   ") }
    }

    @Test
    fun `InProcessDetail round-trips through StepRunDetail serializer`() {
        val detail: StepRunDetail = InProcessDetail(operationId = "op-2", note = "working")

        val encoded = json.encodeToString(detail)
        val decoded = json.decodeFromString<StepRunDetail>(encoded)

        val restored = assertIs<InProcessDetail>(decoded)
        assertEquals(detail, restored)
        assertTrue(encoded.contains("\"type\":\"InProcessDetail\""))
    }
}
