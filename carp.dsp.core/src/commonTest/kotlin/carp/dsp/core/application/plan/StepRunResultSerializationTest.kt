package carp.dsp.core.application.plan

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StepRunResultSerializationTest {

    private val json = Json {
        encodeDefaults = true
    classDiscriminator = "type"
    }

    @Test fun `round-trip succeeded with detail`() {
        val original: StepRunResult =
            StepSucceeded(
                stepId = "s1",
                startedAtEpochMs = 1L,
                durationMs = 2L,
                detail = InProcessDetail(operationId = "op-1")
            )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<StepRunResult>(encoded)

        assertEquals(original, decoded)
        assertTrue(encoded.contains("\"type\":\"Succeeded\""))
    }

    @Test fun `round-trip failed with CommandInfo and CommandDetail`() {
        val original: StepRunResult =
            StepFailed(
                stepId = "s2",
                startedAtEpochMs = 10L,
                durationMs = 20L,
                failure = StepFailure(
                    kind = FailureKind.NON_ZERO_EXIT,
                    message = "Command returned non-zero exit code.",
                    info = CommandInfo(
                        executable = "python",
                        args = listOf("-c", "exit(2)"),
                        exitCode = 2,
                        stderr = "boom",
                    ),
                ),
                detail = CommandDetail(
                    executable = "python",
                    args = listOf("-c", "exit(2)"),
                    exitCode = 2,
                    stderr = "boom",
                ),
            )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<StepRunResult>(encoded)

        assertEquals(original, decoded)
        assertTrue(encoded.contains("NON_ZERO_EXIT"))
        assertTrue(encoded.contains("\"type\":\"Failed\""))
    }

    @Test fun `round-trip skipped`() {
        val original: StepRunResult = StepSkipped(stepId = "s3", reason = "precondition unmet")

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<StepRunResult>(encoded)

        assertEquals(original, decoded)
        assertTrue(encoded.contains("\"type\":\"Skipped\""))
    }
}
