package carp.dsp.core.application.execution

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StepFailureSerializationTest {

    private val json = Json {
        encodeDefaults = true
        classDiscriminator = "type"
    }

    @Test
    fun `round-trip with CommandInfo preserves fields`() {
        val original = StepFailure(
            kind = FailureKind.NON_ZERO_EXIT,
            message = "non-zero",
            cause = "exit 2",
            info = CommandInfo(
                executable = "python",
                args = listOf("-c", "exit(2)"),
                exitCode = 2,
                timedOut = false,
                stdout = null,
                stderr = "boom"
            )
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<StepFailure>(encoded)

        assertEquals(original, decoded)
        assertTrue(encoded.contains("\"type\":\"CommandInfo\""))
    }

    @Test
    fun `round-trip with InProcessInfo preserves fields`() {
        val original = StepFailure(
            kind = FailureKind.INTERNAL_ERROR,
            message = "failed",
            info = InProcessInfo(
                operationId = "op-1",
                note = "note"
            )
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<StepFailure>(encoded)

        assertEquals(original, decoded)
        assertTrue(encoded.contains("\"type\":\"InProcessInfo\""))
    }
}

