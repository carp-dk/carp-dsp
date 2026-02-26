package carp.dsp.core.application.execution

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class StepRunResultDefaultsTest {

    private val json = Json {
        encodeDefaults = true
    classDiscriminator = "type"
    }

    @Test fun `null timestamps and optional fields round-trip`() {
        val original: StepRunResult =
            StepFailed(
                stepId = "s",
                failure = StepFailure(
                    kind = FailureKind.SPAWN_FAILED,
                    message = "Could not spawn",
                    cause = null,
                    info = null
                ),
                detail = null
            )

        val decoded = json.decodeFromString<StepRunResult>(json.encodeToString(original))
        assertEquals(original, decoded)
    }

    @Test fun `CONFIG_ERROR round-trips with detail`() {
        val original: StepRunResult = StepFailed(
            stepId = "cfg",
            failure = StepFailure(
                kind = FailureKind.CONFIG_ERROR,
                message = "Invalid configuration",
                cause = "missing field",
                info = null
            ),
            detail = CommandDetail(
                executable = "python",
                args = listOf("script.py"),
                exitCode = 2,
                timedOut = false,
                stdout = null,
                stderr = "threshold required"
            )
        )

        val decoded = json.decodeFromString<StepRunResult>(json.encodeToString(original))
        assertEquals(original, decoded)
    }
}
