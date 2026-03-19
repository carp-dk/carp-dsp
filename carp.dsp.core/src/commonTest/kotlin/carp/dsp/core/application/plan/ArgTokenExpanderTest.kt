package carp.dsp.core.application.plan

import dk.cachet.carp.analytics.application.plan.*
import dk.cachet.carp.analytics.domain.data.FileFormat
import dk.cachet.carp.analytics.domain.data.FileLocation
import dk.cachet.carp.analytics.domain.data.InputDataSpec
import dk.cachet.carp.analytics.domain.data.OutputDataSpec
import dk.cachet.carp.analytics.domain.tasks.*
import dk.cachet.carp.common.application.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Test suite for ArgTokenExpander.
 *
 * Tests that argument tokens are correctly expanded to semantic ExpandedArg objects
 * with proper error/warning collection for missing bindings and unimplemented features.
 */
class ArgTokenExpanderTest
{
    private val expander = ArgTokenExpander()

    // Test data
    private val inputId = UUID.randomUUID()
    private val outputId = UUID.randomUUID()
    private val stepId = UUID.randomUUID()

    // ── Literal Token Tests ───────────────────────────────────────────────────

    @Test
    fun `expand Literal token passes through unchanged`()
    {
        val tokens = listOf<ArgToken>(
            Literal( "preprocess.py" )
        )
        val bindings = emptyResolvedBindings()
        val issues = mutableListOf<PlanIssue>()

        val result = expander.expand( tokens, bindings, issues )

        assertEquals( 1, result.size )
        assertIs<ExpandedArg.Literal>( result[0] )
        assertEquals( "preprocess.py", ( result[0] as ExpandedArg.Literal ).value )
        assertEquals( 0, issues.size )
    }

    @Test
    fun `expand multiple Literal tokens in sequence`()
    {
        val tokens = listOf<ArgToken>(
            Literal( "python" ),
            Literal( "script.py" ),
            Literal( "--verbose" ),
            Literal( "--output-format=json" )
        )
        val bindings = emptyResolvedBindings()
        val issues = mutableListOf<PlanIssue>()

        val result = expander.expand( tokens, bindings, issues )

        assertEquals( 4, result.size )
        result.forEachIndexed { _, arg ->
            assertIs<ExpandedArg.Literal>( arg )
        }
        assertEquals( 0, issues.size )
    }

    // ── InputRef Token Tests ──────────────────────────────────────────────────

    @Test
    fun `expand InputRef with valid binding resolves to DataReference`()
    {
        val tokens = listOf<ArgToken>(
            InputRef( inputId )
        )
        val bindings = resolvedBindingsWithInput( inputId )
        val issues = mutableListOf<PlanIssue>()

        val result = expander.expand( tokens, bindings, issues )

        assertEquals( 1, result.size )
        assertIs<ExpandedArg.DataReference>( result[0] )
        assertEquals( 0, issues.size )
    }

    @Test
    fun `expand InputRef with missing binding collects error`()
    {
        val missingInputId = UUID.randomUUID()
        val tokens = listOf<ArgToken>(
            InputRef( missingInputId )
        )
        val bindings = emptyResolvedBindings()
        val issues = mutableListOf<PlanIssue>()

        val result = expander.expand( tokens, bindings, issues )

        assertEquals( 0, result.size ) // Token is filtered out
        assertEquals( 1, issues.size )
        val issue = issues[0]
        assertEquals( PlanIssueSeverity.ERROR, issue.severity )
        assertEquals( "MISSING_INPUT_BINDING", issue.code )
        assertTrue( issue.message.contains( missingInputId.toString() ) )
    }

    @Test
    fun `expand InputRef with optional stepId in error`()
    {
        val missingInputId = UUID.randomUUID()
        val tokens = listOf<ArgToken>(
            InputRef( missingInputId )
        )
        val bindings = emptyResolvedBindings()
        val issues = mutableListOf<PlanIssue>()

        expander.expand( tokens, bindings, issues, stepId )

        assertEquals( 1, issues.size )
        val issue = issues[0]
        assertEquals( stepId, issue.stepId )
    }

    // ── OutputRef Token Tests ─────────────────────────────────────────────────

    @Test
    fun `expand OutputRef with valid binding resolves to DataReference`()
    {
        val tokens = listOf<ArgToken>(
            OutputRef( outputId )
        )
        val bindings = resolvedBindingsWithOutput( outputId )
        val issues = mutableListOf<PlanIssue>()

        val result = expander.expand( tokens, bindings, issues )

        assertEquals( 1, result.size )
        assertIs<ExpandedArg.DataReference>( result[0] )
        assertEquals( 0, issues.size )
    }

    @Test
    fun `expand OutputRef with missing binding collects error`()
    {
        val missingOutputId = UUID.randomUUID()
        val tokens = listOf<ArgToken>(
            OutputRef( missingOutputId )
        )
        val bindings = emptyResolvedBindings()
        val issues = mutableListOf<PlanIssue>()

        val result = expander.expand( tokens, bindings, issues )

        assertEquals( 0, result.size ) // Token is filtered out
        assertEquals( 1, issues.size )
        val issue = issues[0]
        assertEquals( PlanIssueSeverity.ERROR, issue.severity )
        assertEquals( "MISSING_OUTPUT_BINDING", issue.code )
        assertTrue( issue.message.contains( missingOutputId.toString() ) )
    }

    // ── Path Substitution Token Tests ─────────────────────────────────────────

    @Test
    fun `expand InputPathSubstitutionRef creates PathSubstitution`()
    {
        val template = "--input=$()"
        val tokens = listOf<ArgToken>(
            InputPathSubstitutionRef( inputId, template )
        )
        val bindings = emptyResolvedBindings()
        val issues = mutableListOf<PlanIssue>()

        val result = expander.expand( tokens, bindings, issues )

        assertEquals( 1, result.size )
        assertIs<ExpandedArg.PathSubstitution>( result[0] )
        assertEquals( inputId, ( result[0] as ExpandedArg.PathSubstitution ).id )
        assertEquals( template, ( result[0] as ExpandedArg.PathSubstitution ).template )
        assertEquals( 0, issues.size )
    }

    @Test
    fun `expand OutputPathSubstitutionRef creates PathSubstitution`()
    {
        val template = "--output=$()"
        val tokens = listOf<ArgToken>(
            OutputPathSubstitutionRef( outputId, template )
        )
        val bindings = emptyResolvedBindings()
        val issues = mutableListOf<PlanIssue>()

        val result = expander.expand( tokens, bindings, issues )

        assertEquals( 1, result.size )
        assertIs<ExpandedArg.PathSubstitution>( result[0] )
        assertEquals( outputId, ( result[0] as ExpandedArg.PathSubstitution ).id )
        assertEquals( template, ( result[0] as ExpandedArg.PathSubstitution ).template )
        assertEquals( 0, issues.size )
    }

    // ── ParamRef Token Tests ──────────────────────────────────────────────────

    @Test
    fun `expand ParamRef collects warning and returns placeholder Literal`()
    {
        val paramName = "MODEL_VERSION"
        val tokens = listOf<ArgToken>(
            ParamRef( paramName )
        )
        val bindings = emptyResolvedBindings()
        val issues = mutableListOf<PlanIssue>()

        val result = expander.expand( tokens, bindings, issues )

        assertEquals( 1, result.size )
        assertIs<ExpandedArg.Literal>( result[0] )
        assertEquals( "\${$paramName}", ( result[0] as ExpandedArg.Literal ).value )

        assertEquals( 1, issues.size )
        val issue = issues[0]
        assertEquals( PlanIssueSeverity.WARNING, issue.severity )
        assertEquals( "PARAM_REF_NOT_IMPLEMENTED", issue.code )
        assertTrue( issue.message.contains( paramName ) )
    }

    // ── Mixed Token Tests ─────────────────────────────────────────────────────

    @Test
    fun `expand mixed tokens with valid bindings`()
    {
        val tokens = listOf(
            Literal( "analyze.py" ),
            InputRef( inputId ),
            OutputRef( outputId ),
            Literal( "--format=json" )
        )
        val bindings = resolvedBindingsWithInputAndOutput( inputId, outputId )
        val issues = mutableListOf<PlanIssue>()

        val result = expander.expand( tokens, bindings, issues )

        assertEquals( 4, result.size )
        assertIs<ExpandedArg.Literal>( result[0] )
        assertIs<ExpandedArg.DataReference>( result[1] )
        assertIs<ExpandedArg.DataReference>( result[2] )
        assertIs<ExpandedArg.Literal>( result[3] )
        assertEquals( 0, issues.size )
    }

    @Test
    fun `expand mixed tokens with some missing bindings`()
    {
        val missingInputId = UUID.randomUUID()
        val tokens = listOf(
            Literal( "analyze.py" ),
            InputRef( inputId ),
            InputRef( missingInputId ),
            OutputRef( outputId ),
            Literal( "--format=json" )
        )
        val bindings = resolvedBindingsWithInputAndOutput( inputId, outputId )
        val issues = mutableListOf<PlanIssue>()

        val result = expander.expand( tokens, bindings, issues )

        // Missing token is filtered out
        assertEquals( 4, result.size )
        assertIs<ExpandedArg.Literal>( result[0] )
        assertIs<ExpandedArg.DataReference>( result[1] )
        assertIs<ExpandedArg.DataReference>( result[2] )
        assertIs<ExpandedArg.Literal>( result[3] )

        // Error is collected
        assertEquals( 1, issues.size )
        assertEquals( PlanIssueSeverity.ERROR, issues[0].severity )
    }

    @Test
    fun `expand tokens with complex combinations`()
    {
        val tokens = listOf(
            Literal( "python" ),
            Literal( "pipeline.py" ),
            InputRef( inputId ),
            InputPathSubstitutionRef( inputId, "--input=$()"),
            OutputRef( outputId ),
            OutputPathSubstitutionRef( outputId, "--output=$()"),
            ParamRef( "BATCH_SIZE" ),
            Literal( "--verbose" )
        )
        val bindings = resolvedBindingsWithInputAndOutput( inputId, outputId )
        val issues = mutableListOf<PlanIssue>()

        val result = expander.expand( tokens, bindings, issues )

        assertEquals( 8, result.size )
        assertEquals( 1, issues.size ) // ParamRef warning
    }

    // ── Empty and Edge Cases ──────────────────────────────────────────────────

    @Test
    fun `expand empty token list returns empty result`()
    {
        val tokens = emptyList<ArgToken>()
        val bindings = emptyResolvedBindings()
        val issues = mutableListOf<PlanIssue>()

        val result = expander.expand( tokens, bindings, issues )

        assertEquals( 0, result.size )
        assertEquals( 0, issues.size )
    }

    @Test
    fun `expand with no stepId in error reporting`()
    {
        val missingInputId = UUID.randomUUID()
        val tokens = listOf<ArgToken>(
            InputRef( missingInputId )
        )
        val bindings = emptyResolvedBindings()
        val issues = mutableListOf<PlanIssue>()

        expander.expand( tokens, bindings, issues ) // No stepId parameter

        assertEquals( 1, issues.size )
        val issue = issues[0]
        assertEquals( null, issue.stepId )
    }

    @Test
    fun `expand collects multiple errors from different missing bindings`()
    {
        val missingInputId1 = UUID.randomUUID()
        val missingInputId2 = UUID.randomUUID()
        val tokens = listOf<ArgToken>(
            InputRef( missingInputId1 ),
            InputRef( missingInputId2 )
        )
        val bindings = emptyResolvedBindings()
        val issues = mutableListOf<PlanIssue>()

        val result = expander.expand( tokens, bindings, issues )

        assertEquals( 0, result.size )
        assertEquals( 2, issues.size )
        assertTrue( issues.all { it.severity == PlanIssueSeverity.ERROR } )
    }

    // ── Helper Methods ────────────────────────────────────────────────────────

    private fun emptyResolvedBindings(): ResolvedBindings =
        ResolvedBindings( inputs = emptyMap(), outputs = emptyMap() )

    private fun resolvedBindingsWithInput( id: UUID ): ResolvedBindings
    {
        // ✅ Use FileLocation directly (unified model)
        val location = FileLocation(
            path = "/input.csv",
            format = FileFormat.CSV
        )

        val spec = InputDataSpec(
            id = id,
            name = "test-input",
            location = location,
            stepRef = null
        )

        val resolvedInput = ResolvedInput( spec = spec, location = location )

        return ResolvedBindings(
            inputs = mapOf( id to resolvedInput ),
            outputs = emptyMap()
        )
    }

    private fun resolvedBindingsWithOutput( id: UUID ): ResolvedBindings
    {
        // ✅ Use FileLocation directly (unified model)
        val location = FileLocation(
            path = "/output.csv",
            format = FileFormat.CSV
        )

        val spec = OutputDataSpec(
            id = id,
            name = "test-output",
            location = location
        )

        val loc = FileLocation(
            path = "/output.csv",
            format = FileFormat.CSV,
            metadata = emptyMap()
        )

        val resolvedOutput = ResolvedOutput( spec = spec, location = loc )

        return ResolvedBindings(
            inputs = emptyMap(),
            outputs = mapOf( id to resolvedOutput )
        )
    }

    private fun resolvedBindingsWithInputAndOutput(
        inputId: UUID,
        outputId: UUID
    ): ResolvedBindings
    {
        // ✅ Input with FileLocation (unified model)
        val inputLocation = FileLocation(
            path = "/input.csv",
            format = FileFormat.CSV
        )

        val inputSpec = InputDataSpec(
            id = inputId,
            name = "test-input",
            location = inputLocation,
            stepRef = null
        )

        val resolvedInput = ResolvedInput( spec = inputSpec, location = inputLocation )

        // ✅ Output with FileLocation (unified model)
        val outputLocation = FileLocation(
            path = "/output.csv",
            format = FileFormat.CSV
        )

        val outputSpec = OutputDataSpec(
            id = outputId,
            name = "test-output",
            location = outputLocation
        )

        val loc = FileLocation(
            path = "/output.csv",
            format = FileFormat.CSV,
            metadata = emptyMap()
        )

        val resolvedOutput = ResolvedOutput( spec = outputSpec, location = loc )

        return ResolvedBindings(
            inputs = mapOf( inputId to resolvedInput ),
            outputs = mapOf( outputId to resolvedOutput )
        )
    }
}
