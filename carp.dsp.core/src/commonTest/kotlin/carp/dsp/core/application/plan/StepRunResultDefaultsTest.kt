package carp.dsp.core.application.plan

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
                failure = StepFailure(kind = FailureKind.SPAWN_FAILED, message = "Could not spawn", cause = null, info = null),
                detail = null
            )

        val decoded = json.decodeFromString<StepRunResult>(json.encodeToString(original))
        assertEquals(original, decoded)
    }
}
