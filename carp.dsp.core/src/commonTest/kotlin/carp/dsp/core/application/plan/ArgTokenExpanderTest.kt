package carp.dsp.core.application.plan

import dk.cachet.carp.analytics.application.plan.DataRef
import dk.cachet.carp.analytics.application.plan.ExpandedArg
import dk.cachet.carp.analytics.application.plan.PlanIssue
import dk.cachet.carp.analytics.application.plan.ResolvedBindings
import dk.cachet.carp.analytics.domain.tasks.InputPathSubstitutionRef
import dk.cachet.carp.analytics.domain.tasks.InputRef
import dk.cachet.carp.analytics.domain.tasks.Literal
import dk.cachet.carp.analytics.domain.tasks.OutputPathSubstitutionRef
import dk.cachet.carp.analytics.domain.tasks.OutputRef
import dk.cachet.carp.analytics.domain.tasks.ParamRef
import dk.cachet.carp.common.application.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
        assertIs<ExpandedArg.Literal>(result[0])
        assertEquals("--input", (result[0] as ExpandedArg.Literal).value)
        assertIs<ExpandedArg.Literal>(result[1])
        assertEquals("file.txt", (result[1] as ExpandedArg.Literal).value)
        assertIs<ExpandedArg.Literal>(result[2])
        assertEquals("--verbose", (result[2] as ExpandedArg.Literal).value)
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
        assertIs<ExpandedArg.DataReference>(result[0])
        assertEquals(dataRefId, (result[0] as ExpandedArg.DataReference).dataRefId)
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
        assertIs<ExpandedArg.DataReference>(result[0])
        assertEquals(dataRefId, (result[0] as ExpandedArg.DataReference).dataRefId)
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
        assertIs<ExpandedArg.Literal>(result[0])
        assertEquals("\${batch-size}", (result[0] as ExpandedArg.Literal).value)
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
        assertIs<ExpandedArg.Literal>(result[0])
        assertEquals("python", (result[0] as ExpandedArg.Literal).value)
        assertIs<ExpandedArg.Literal>(result[1])
        assertEquals("--input", (result[1] as ExpandedArg.Literal).value)
        assertIs<ExpandedArg.DataReference>(result[2])
        assertEquals(dataRefId, (result[2] as ExpandedArg.DataReference).dataRefId)
        assertIs<ExpandedArg.Literal>(result[3])
        assertEquals("--output", (result[3] as ExpandedArg.Literal).value)
        assertIs<ExpandedArg.DataReference>(result[4])
        assertEquals(outputDataRefId, (result[4] as ExpandedArg.DataReference).dataRefId)
        assertIs<ExpandedArg.Literal>(result[5])
        assertEquals("--batch-size", (result[5] as ExpandedArg.Literal).value)
        assertIs<ExpandedArg.Literal>(result[6])
        assertEquals("\${batch_size}", (result[6] as ExpandedArg.Literal).value)

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
        assertIs<ExpandedArg.Literal>(result[0])
        assertEquals("\${test-param}", (result[0] as ExpandedArg.Literal).value)
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
        assertIs<ExpandedArg.Literal>(result[0])
        assertEquals("\${test-param}", (result[0] as ExpandedArg.Literal).value)
        assertEquals(1, issues.size)
        assertEquals(null, issues[0].stepId)
    }

    @Test
    fun literal_expanded_to_literal() {
        val tokens = listOf(Literal("--output-file"))
        val bindings = ResolvedBindings()
        val issues = mutableListOf<PlanIssue>()

        val result = expander.expand(tokens, bindings, issues)

        assertEquals(1, result.size)
        assertIs<ExpandedArg.Literal>(result[0])
        assertEquals("--output-file", (result[0] as ExpandedArg.Literal).value)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun direct_input_reference_expanded_to_data_reference() {
        val inputId = UUID.randomUUID()
        val expectedUuid = UUID.randomUUID()
        val bindings = ResolvedBindings(
            inputs = mapOf(inputId to DataRef(expectedUuid, "csv"))
        )
        val tokens = listOf(InputRef(inputId))
        val issues = mutableListOf<PlanIssue>()

        val result = expander.expand(tokens, bindings, issues)

        assertEquals(1, result.size)
        assertIs<ExpandedArg.DataReference>(result[0])
        val dataRef = result[0] as ExpandedArg.DataReference
        assertEquals(expectedUuid, dataRef.dataRefId)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun input_path_substitution_expanded_to_path_substitution() {
        val inputId = UUID.randomUUID()
        val bindings = ResolvedBindings(
            inputs = mapOf(inputId to DataRef(UUID.randomUUID(), "csv"))
        )
        val tokens = listOf(InputPathSubstitutionRef(inputId, "--input=$()"))
        val issues = mutableListOf<PlanIssue>()

        val result = expander.expand(tokens, bindings, issues)

        assertEquals(1, result.size)
        assertIs<ExpandedArg.PathSubstitution>(result[0])
        val pathSubst = result[0] as ExpandedArg.PathSubstitution
        assertEquals(inputId, pathSubst.dataRefId)
        assertEquals("--input=$()", pathSubst.template)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun environment_variable_expanded_to_literal() {
        val tokens = listOf(Literal("--model=$(env.MODEL_PATH)"))
        val bindings = ResolvedBindings()
        val issues = mutableListOf<PlanIssue>()

        val result = expander.expand(tokens, bindings, issues)

        assertEquals(1, result.size)
        assertIs<ExpandedArg.Literal>(result[0])
        assertEquals("--model=$(env.MODEL_PATH)", (result[0] as ExpandedArg.Literal).value)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun missing_input_binding_produces_error() {
        val missingInputId = UUID.randomUUID()
        val bindings = ResolvedBindings(inputs = emptyMap())
        val tokens = listOf(InputRef(missingInputId))
        val issues = mutableListOf<PlanIssue>()

        val result = expander.expand(tokens, bindings, issues)

        assertTrue(result.isEmpty())
        assertNotNull(issues.find { it.code == "MISSING_INPUT_BINDING" })
    }

    @Test
    fun mixed_tokens_expanded_correctly() {
        val inputId = UUID.randomUUID()
        val outputId = UUID.randomUUID()
        val inputUuid = UUID.randomUUID()
        val outputUuid = UUID.randomUUID()

        val bindings = ResolvedBindings(
            inputs = mapOf(inputId to DataRef(inputUuid, "csv")),
            outputs = mapOf(outputId to DataRef(outputUuid, "json"))
        )

        val tokens = listOf(
            Literal("analyze.py"),
            InputRef(inputId),
            InputPathSubstitutionRef(inputId, "--input=$()"),
            Literal("--model=$(env.MODEL_PATH)"),
            OutputPathSubstitutionRef(outputId, "--output=$()"),
            Literal("--format=csv")
        )

        val issues = mutableListOf<PlanIssue>()

        val result = expander.expand(tokens, bindings, issues)

        assertEquals(6, result.size)
        assertIs<ExpandedArg.Literal>(result[0])
        assertIs<ExpandedArg.DataReference>(result[1])
        assertIs<ExpandedArg.PathSubstitution>(result[2])
        assertIs<ExpandedArg.Literal>(result[3])
        assertIs<ExpandedArg.PathSubstitution>(result[4])
        assertIs<ExpandedArg.Literal>(result[5])
        assertTrue(issues.isEmpty())
    }
}
