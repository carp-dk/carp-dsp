package carp.dsp.core.application.execution

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

    // Additional comprehensive tests to maximize init block coverage

    @Test
    fun `CommandDetail init validates executable with various edge cases`() {
        // Valid executable cases
        val validDetail1 = CommandDetail(executable = "a", exitCode = 0) // Single character
        assertEquals("a", validDetail1.executable)

        val validDetail2 = CommandDetail(executable = "very-long-executable-name-with-many-characters", exitCode = 0)
        assertEquals("very-long-executable-name-with-many-characters", validDetail2.executable)

        val validDetail3 = CommandDetail(executable = "exec with spaces", exitCode = 0)
        assertEquals("exec with spaces", validDetail3.executable)

        val validDetail4 = CommandDetail(executable = "/path/to/executable", exitCode = 0)
        assertEquals("/path/to/executable", validDetail4.executable)

        // Invalid executable cases - blank strings
        assertFailsWith<IllegalArgumentException>("executable must not be blank.") {
            CommandDetail(executable = "", exitCode = 0)
        }

        assertFailsWith<IllegalArgumentException>("executable must not be blank.") {
            CommandDetail(executable = "   ", exitCode = 0) // Whitespace only
        }

        assertFailsWith<IllegalArgumentException>("executable must not be blank.") {
            CommandDetail(executable = "\t", exitCode = 0) // Tab only
        }

        assertFailsWith<IllegalArgumentException>("executable must not be blank.") {
            CommandDetail(executable = "\n", exitCode = 0) // Newline only
        }

        assertFailsWith<IllegalArgumentException>("executable must not be blank.") {
            CommandDetail(executable = " \t\n ", exitCode = 0) // Mixed whitespace
        }
    }

    @Test
    fun `CommandDetail init validates exitCode with boundary values`() {
        // Valid exitCode cases
        val validDetail1 = CommandDetail(executable = "test", exitCode = 0) // Minimum valid
        assertEquals(0, validDetail1.exitCode)

        val validDetail2 = CommandDetail(executable = "test", exitCode = 1)
        assertEquals(1, validDetail2.exitCode)

        val validDetail3 = CommandDetail(executable = "test", exitCode = 255) // Common max exit code
        assertEquals(255, validDetail3.exitCode)

        val validDetail4 = CommandDetail(executable = "test", exitCode = Int.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, validDetail4.exitCode)

        // Invalid exitCode cases - negative values
        assertFailsWith<IllegalArgumentException>("exitCode must be >= 0.") {
            CommandDetail(executable = "test", exitCode = -1)
        }

        assertFailsWith<IllegalArgumentException>("exitCode must be >= 0.") {
            CommandDetail(executable = "test", exitCode = -100)
        }

        assertFailsWith<IllegalArgumentException>("exitCode must be >= 0.") {
            CommandDetail(executable = "test", exitCode = Int.MIN_VALUE)
        }
    }

    @Test
    fun `CommandDetail init validates both executable and exitCode together`() {
        // Both invalid - should fail on executable first (order matters for init validation)
        assertFailsWith<IllegalArgumentException>("executable must not be blank.") {
            CommandDetail(executable = "", exitCode = -1)
        }

        // Invalid executable, valid exitCode
        assertFailsWith<IllegalArgumentException>("executable must not be blank.") {
            CommandDetail(executable = "  ", exitCode = 0)
        }

        // Valid executable, invalid exitCode
        assertFailsWith<IllegalArgumentException>("exitCode must be >= 0.") {
            CommandDetail(executable = "valid", exitCode = -1)
        }

        // Both valid
        val validDetail = CommandDetail(executable = "valid", exitCode = 42)
        assertEquals("valid", validDetail.executable)
        assertEquals(42, validDetail.exitCode)
    }

    @Test
    fun `InProcessDetail init validates operationId with comprehensive edge cases`() {
        // Valid operationId cases
        val validDetail1 = InProcessDetail(operationId = "a") // Single character
        assertEquals("a", validDetail1.operationId)

        val validDetail2 = InProcessDetail(operationId = "operation-id-123")
        assertEquals("operation-id-123", validDetail2.operationId)

        val validDetail3 = InProcessDetail(operationId = "op_with_underscores")
        assertEquals("op_with_underscores", validDetail3.operationId)

        val validDetail4 = InProcessDetail(operationId = "UUID:123e4567-e89b-12d3-a456-426614174000")
        assertEquals("UUID:123e4567-e89b-12d3-a456-426614174000", validDetail4.operationId)

        val validDetail5 = InProcessDetail(operationId = "op with spaces") // Spaces are valid
        assertEquals("op with spaces", validDetail5.operationId)

        // Invalid operationId cases - blank strings
        assertFailsWith<IllegalArgumentException>("operationId must not be blank.") {
            InProcessDetail(operationId = "")
        }

        assertFailsWith<IllegalArgumentException>("operationId must not be blank.") {
            InProcessDetail(operationId = "   ") // Spaces only
        }

        assertFailsWith<IllegalArgumentException>("operationId must not be blank.") {
            InProcessDetail(operationId = "\t") // Tab only
        }

        assertFailsWith<IllegalArgumentException>("operationId must not be blank.") {
            InProcessDetail(operationId = "\n") // Newline only
        }

        assertFailsWith<IllegalArgumentException>("operationId must not be blank.") {
            InProcessDetail(operationId = "\r\n") // CRLF
        }

        assertFailsWith<IllegalArgumentException>("operationId must not be blank.") {
            InProcessDetail(operationId = " \t\n\r ") // Mixed whitespace
        }
    }

    @Test
    fun `CommandDetail constructor with all parameters works correctly`() {
        val detail = CommandDetail(
            executable = "python",
            args = listOf("-c", "print('hello')"),
            exitCode = 0,
            timedOut = true,
            stdout = "hello\n",
            stderr = "warning: something"
        )

        assertEquals("python", detail.executable)
        assertEquals(listOf("-c", "print('hello')"), detail.args)
        assertEquals(0, detail.exitCode)
        assertTrue(detail.timedOut)
        assertEquals("hello\n", detail.stdout)
        assertEquals("warning: something", detail.stderr)
    }

    @Test
    fun `InProcessDetail constructor with all parameters works correctly`() {
        val detail = InProcessDetail(
            operationId = "complex-operation-123",
            note = "This is a detailed note about the operation"
        )

        assertEquals("complex-operation-123", detail.operationId)
        assertEquals("This is a detailed note about the operation", detail.note)
    }

    @Test
    fun `CommandDetail data class methods work correctly`() {
        val detail = CommandDetail(
            executable = "test",
            args = listOf("arg1", "arg2"),
            exitCode = 0,
            timedOut = false,
            stdout = "output",
            stderr = null
        )

        // Test copy method with parameter changes
        val copied1 = detail.copy(executable = "new-exec")
        assertEquals("new-exec", copied1.executable)
        assertEquals(detail.args, copied1.args) // Other fields unchanged

        val copied2 = detail.copy(exitCode = 1, timedOut = true)
        assertEquals(1, copied2.exitCode)
        assertTrue(copied2.timedOut)
        assertEquals(detail.executable, copied2.executable) // Other fields unchanged

        // Test toString
        val stringRepr = detail.toString()
        assertTrue(stringRepr.contains("CommandDetail"))
        assertTrue(stringRepr.contains("test"))
        assertTrue(stringRepr.contains("arg1"))

        // Test component functions
        assertEquals("test", detail.component1()) // executable
        assertEquals(listOf("arg1", "arg2"), detail.component2()) // args
        assertEquals(0, detail.component3()) // exitCode
        assertEquals(false, detail.component4()) // timedOut
        assertEquals("output", detail.component5()) // stdout
        assertEquals(null, detail.component6()) // stderr
    }

    @Test
    fun `InProcessDetail data class methods work correctly`() {
        val detail = InProcessDetail(
            operationId = "op-123",
            note = "test note"
        )

        // Test copy method
        val copied1 = detail.copy(operationId = "new-op-456")
        assertEquals("new-op-456", copied1.operationId)
        assertEquals(detail.note, copied1.note) // Other field unchanged

        val copied2 = detail.copy(note = "updated note")
        assertEquals("updated note", copied2.note)
        assertEquals(detail.operationId, copied2.operationId) // Other field unchanged

        val copied3 = detail.copy(note = null)
        assertNull(copied3.note)

        // Test toString
        val stringRepr = detail.toString()
        assertTrue(stringRepr.contains("InProcessDetail"))
        assertTrue(stringRepr.contains("op-123"))
        assertTrue(stringRepr.contains("test note"))

        // Test component functions
        assertEquals("op-123", detail.component1()) // operationId
        assertEquals("test note", detail.component2()) // note
    }

    @Test
    fun `StepRunDetail sealed interface polymorphism works correctly`() {
        val details: List<StepRunDetail> = listOf(
            CommandDetail(executable = "cmd", exitCode = 0),
            InProcessDetail(operationId = "op")
        )

        // Test polymorphic behavior in a list
        val results = details.map { detail ->
            when (detail) {
                is CommandDetail -> "command: ${detail.executable}"
                is InProcessDetail -> "process: ${detail.operationId}"
            }
        }

        assertEquals(2, results.size)
        assertEquals("command: cmd", results[0])
        assertEquals("process: op", results[1])

        // Test individual type checks
        val commandDetail = details[0]
        val inProcessDetail = details[1]

        // Test that sealed interface allows proper type discrimination
        assertTrue(commandDetail is CommandDetail)
        assertTrue(inProcessDetail is InProcessDetail)
        assertTrue(commandDetail::class != inProcessDetail::class)
    }

    @Test
    fun `serialization handles edge case values correctly`() {
        // Test CommandDetail with edge case values
        val commandWithEdgeCases = CommandDetail(
            executable = "a",
            args = listOf("", " ", "\t", "\n", "normal-arg"),
            exitCode = 0,
            timedOut = true,
            stdout = "",
            stderr = ""
        )

        val encoded1 = json.encodeToString<StepRunDetail>(commandWithEdgeCases)
        val decoded1 = json.decodeFromString<StepRunDetail>(encoded1)
        assertEquals(commandWithEdgeCases, decoded1)

        // Test InProcessDetail with edge case values
        val inProcessWithEdgeCases = InProcessDetail(
            operationId = "a",
            note = ""
        )

        val encoded2 = json.encodeToString<StepRunDetail>(inProcessWithEdgeCases)
        val decoded2 = json.decodeFromString<StepRunDetail>(encoded2)
        assertEquals(inProcessWithEdgeCases, decoded2)
    }
}
