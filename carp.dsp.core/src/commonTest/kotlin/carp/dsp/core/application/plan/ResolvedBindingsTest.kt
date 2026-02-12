package carp.dsp.core.application.plan

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ResolvedBindingsTest {

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    @Test
    fun `ResolvedBindings serializes and deserializes`() {
        val bindings = ResolvedBindings(
            inputs = mapOf("in" to DataRef("id-in", "text/plain")),
            outputs = mapOf("out" to DataSinkRef("id-out", "text/plain"))
        )

        val encoded = json.encodeToString(bindings)
        val decoded = json.decodeFromString<ResolvedBindings>(encoded)

        assertEquals(bindings, decoded)
    }

    @Test
    fun `ResolvedBindings defensive copies maps`() {
        val inputs = mutableMapOf("in" to DataRef("id-in", "t"))
        val outputs = mutableMapOf("out" to DataSinkRef("id-out", "t"))

        val bindings = ResolvedBindings(inputs, outputs)

        inputs["in"] = DataRef("changed", "t")
        outputs["out"] = DataSinkRef("changed", "t")

        assertEquals("id-in", bindings.safeInputs["in"]?.id)
        assertEquals("id-out", bindings.safeOutputs["out"]?.id)
    }

    @Test
    fun `requireInput and requireOutput return bindings and throw with helpful message`() {
        val bindings = ResolvedBindings(
            inputs = mapOf("a" to DataRef("id-a", "t")),
            outputs = mapOf("b" to DataSinkRef("id-b", "t"))
        )

        assertEquals("id-a", bindings.requireInput("a").id)
        assertEquals("id-b", bindings.requireOutput("b").id)

        val e1 = assertFailsWith<IllegalArgumentException> { bindings.requireInput("missing") }
        val e2 = assertFailsWith<IllegalArgumentException> { bindings.requireOutput("missing") }

        // ensure message is informative (not brittle exact match)
        assert(e1.message!!.contains("Missing required input binding"))
        assert(e2.message!!.contains("Missing required output binding"))
    }

    @Test
    fun `DataRef and DataSinkRef validate fields`() {
        assertFailsWith<IllegalArgumentException> { DataRef("", "t") }
        assertFailsWith<IllegalArgumentException> { DataRef("id", "") }
        assertFailsWith<IllegalArgumentException> { DataSinkRef("", "t") }
        assertFailsWith<IllegalArgumentException> { DataSinkRef("id", "") }
    }

    @Test
    fun `ResolvedBindings rejects blank keys`() {
        assertFailsWith<IllegalArgumentException> {
            ResolvedBindings(inputs = mapOf(" " to DataRef("id", "t")))
        }
        assertFailsWith<IllegalArgumentException> {
            ResolvedBindings(outputs = mapOf(" " to DataSinkRef("id", "t")))
        }
    }
}
