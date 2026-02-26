package carp.dsp.core.application.plan

import dk.cachet.carp.analytics.application.plan.DataRef
import dk.cachet.carp.analytics.application.plan.PlanIssue
import dk.cachet.carp.analytics.application.plan.ResolvedBindings
import dk.cachet.carp.analytics.domain.tasks.InputRef
import dk.cachet.carp.analytics.domain.tasks.Literal
import dk.cachet.carp.analytics.domain.tasks.OutputRef
import dk.cachet.carp.analytics.domain.tasks.ParamRef
import dk.cachet.carp.common.application.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Test suite for ArgTokenExpander.
 */
class ArgTokenExpanderTest {

    private val expander = ArgTokenExpander()

    @Test
    fun `expand handles Literal tokens correctly`() {
        val tokens = listOf(
            Literal("--input"),
            Literal("file.txt"),
            Literal("--verbose")
        )
        val bindings = ResolvedBindings()
        val issues = mutableListOf<PlanIssue>()

        val result = expander.expand(tokens, bindings, issues, UUID.randomUUID())

        assertEquals(3, result.size)
        assertEquals("--input", result[0])
        assertEquals("file.txt", result[1])
        assertEquals("--verbose", result[2])
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `expand handles InputRef tokens with valid bindings`() {
        val inputId = UUID.randomUUID()
        val dataRefId = UUID.randomUUID()
        val stepId = UUID.randomUUID()
        val tokens = listOf(InputRef(inputId))
        val bindings = ResolvedBindings(
            inputs = mapOf(inputId to DataRef(dataRefId, "text/plain"))
        )
        val issues = mutableListOf<PlanIssue>()

        val result = expander.expand(tokens, bindings, issues, stepId)

        assertEquals(1, result.size)
        assertEquals(dataRefId.toString(), result[0])
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `expand handles OutputRef tokens with valid bindings`() {
        val outputId = UUID.randomUUID()
        val dataRefId = UUID.randomUUID()
        val stepId = UUID.randomUUID()
        val tokens = listOf(OutputRef(outputId))
        val bindings = ResolvedBindings(
            outputs = mapOf(outputId to DataRef(dataRefId, "application/json"))
        )
        val issues = mutableListOf<PlanIssue>()

        val result = expander.expand(tokens, bindings, issues, stepId)

        assertEquals(1, result.size)
        assertEquals(dataRefId.toString(), result[0])
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `expand handles missing InputRef bindings`() {
        val inputId = UUID.randomUUID()
        val stepId = UUID.randomUUID()
        val tokens = listOf(InputRef(inputId))
        val bindings = ResolvedBindings() // Empty bindings
        val issues = mutableListOf<PlanIssue>()

        val result = expander.expand(tokens, bindings, issues, stepId)

        assertTrue(result.isEmpty()) // mapNotNull filters out null results
        assertEquals(1, issues.size)
        assertEquals("MISSING_INPUT_BINDING", issues[0].code)
        assertEquals(stepId, issues[0].stepId)
        assertTrue(issues[0].message.contains(inputId.toString()))
    }

    @Test
    fun `expand handles missing OutputRef bindings`() {
        val outputId = UUID.randomUUID()
        val stepId = UUID.randomUUID()
        val tokens = listOf(OutputRef(outputId))
        val bindings = ResolvedBindings() // Empty bindings
        val issues = mutableListOf<PlanIssue>()

        val result = expander.expand(tokens, bindings, issues, stepId)

        assertTrue(result.isEmpty()) // mapNotNull filters out null results
        assertEquals(1, issues.size)
        assertEquals("MISSING_OUTPUT_BINDING", issues[0].code)
        assertEquals(stepId, issues[0].stepId)
        assertTrue(issues[0].message.contains(outputId.toString()))
    }

    @Test
    fun `expand handles ParamRef tokens as placeholders`() {
        val stepId = UUID.randomUUID()
        val tokens = listOf(ParamRef("batch-size"))
        val bindings = ResolvedBindings()
        val issues = mutableListOf<PlanIssue>()

        val result = expander.expand(tokens, bindings, issues, stepId)

        assertEquals(1, result.size)
        assertEquals("\${batch-size}", result[0])
        assertEquals(1, issues.size)
        assertEquals("PARAM_REF_NOT_IMPLEMENTED", issues[0].code)
        assertEquals(stepId, issues[0].stepId)
        assertTrue(issues[0].message.contains("batch-size"))
    }

    @Test
    fun `expand handles mixed token types`() {
        val inputId = UUID.randomUUID()
        val outputId = UUID.randomUUID()
        val dataRefId = UUID.randomUUID()
        val outputDataRefId = UUID.randomUUID()
        val stepId = UUID.randomUUID()

        val tokens = listOf(
            Literal("python"),
            Literal("--input"),
            InputRef(inputId),
            Literal("--output"),
            OutputRef(outputId),
            Literal("--batch-size"),
            ParamRef("batch_size")
        )

        val bindings = ResolvedBindings(
            inputs = mapOf(inputId to DataRef(dataRefId, "text/plain")),
            outputs = mapOf(outputId to DataRef(outputDataRefId, "application/json"))
        )
        val issues = mutableListOf<PlanIssue>()

        val result = expander.expand(tokens, bindings, issues, stepId)

        assertEquals(7, result.size)
        assertEquals("python", result[0])
        assertEquals("--input", result[1])
        assertEquals(dataRefId.toString(), result[2])
        assertEquals("--output", result[3])
        assertEquals(outputDataRefId.toString(), result[4])
        assertEquals("--batch-size", result[5])
        assertEquals("\${batch_size}", result[6])

        // Should have one warning for ParamRef
        assertEquals(1, issues.size)
        assertEquals("PARAM_REF_NOT_IMPLEMENTED", issues[0].code)
        assertEquals(stepId, issues[0].stepId)
    }

    @Test
    fun `expand handles empty token list`() {
        val stepId = UUID.randomUUID()
        val tokens = emptyList<dk.cachet.carp.analytics.domain.tasks.ArgToken>()
        val bindings = ResolvedBindings()
        val issues = mutableListOf<PlanIssue>()

        val result = expander.expand(tokens, bindings, issues, stepId)

        assertTrue(result.isEmpty())
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `expand works with null stepId`() {
        val tokens = listOf(ParamRef("test-param"))
        val bindings = ResolvedBindings()
        val issues = mutableListOf<PlanIssue>()

        val result = expander.expand(tokens, bindings, issues, null)

        assertEquals(1, result.size)
        assertEquals("\${test-param}", result[0])
        assertEquals(1, issues.size)
        assertEquals(null, issues[0].stepId)
    }

    @Test
    fun `expand works when stepId parameter is omitted (default value)`() {
        val tokens = listOf(ParamRef("test-param"))
        val bindings = ResolvedBindings()
        val issues = mutableListOf<PlanIssue>()

        val result = expander.expand(tokens, bindings, issues) // No stepId provided

        assertEquals(1, result.size)
        assertEquals("\${test-param}", result[0])
        assertEquals(1, issues.size)
        assertEquals(null, issues[0].stepId)
    }
}
