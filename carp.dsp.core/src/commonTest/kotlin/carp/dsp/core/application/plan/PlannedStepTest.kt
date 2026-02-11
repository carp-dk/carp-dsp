package carp.dsp.core.application.plan

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlannedStepTest {

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        classDiscriminator = "type"
    }

    @Test
    fun `serialization round-trip preserves CommandRun planned step`() {
        val step = PlannedStep(
            stepId = "s1",
            name = "Example Command Step",
            process = CommandRun(
                executable = "echo",
                args = listOf("hello"),
                cwd = "/tmp",
                envVars = mapOf("A" to "B"),
                stdin = null,
                timeoutMs = 1_000L
            ),
            bindings = ResolvedBindings(
                inputs = mapOf("in" to DataRef("data-in", "text/plain")),
                outputs = mapOf("out" to DataSinkRef("data-out", "text/plain"))
            ),
            environmentKey = "env:py-311",
        )

        val encoded = json.encodeToString(step)
        val decoded = json.decodeFromString<PlannedStep>(encoded)

        assertEquals(step, decoded)
    }

    @Test
    fun `serialization round-trip preserves InProcessRun planned step`() {
        val step = PlannedStep(
            stepId = "s2",
            name = "Example In-Process Step",
            process = InProcessRun(
                operationId = "analysis.example.v1",
                parameters = mapOf("k" to "v")
            ),
            bindings = ResolvedBindings(
                inputs = emptyMap(),
                outputs = mapOf("out" to DataSinkRef("result-id", "application/json"))
            ),
            environmentKey = null,
        )

        val encoded = json.encodeToString(step)
        val decoded = json.decodeFromString<PlannedStep>(encoded)

        assertEquals(step, decoded)
    }

    @Test
    fun `constructor validates required fields`() {
        assertFailsWith<IllegalArgumentException> {
            PlannedStep(
                stepId = "",
                name = "ok",
                process = InProcessRun("op"),
                bindings = ResolvedBindings()
            )
        }

        assertFailsWith<IllegalArgumentException> {
            PlannedStep(
                stepId = "ok",
                name = "",
                process = InProcessRun("op"),
                bindings = ResolvedBindings()
            )
        }

        assertFailsWith<IllegalArgumentException> {
            PlannedStep(
                stepId = "ok",
                name = "ok",
                process = InProcessRun("op"),
                bindings = ResolvedBindings(),
                environmentKey = "   "
            )
        }
    }

    @Test
    fun `bindings are preserved and accessible`() {
        val bindings = ResolvedBindings(
            inputs = mapOf("a" to DataRef("id-a", "t")),
            outputs = mapOf("b" to DataSinkRef("id-b", "t")),
        )

        val step = PlannedStep(
            stepId = "s",
            name = "Step",
            process = InProcessRun("op"),
            bindings = bindings
        )

        assertEquals("id-a", step.bindings.requireInput("a").id)
        assertEquals("id-b", step.bindings.requireOutput("b").id)
    }
}
