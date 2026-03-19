package carp.dsp.core.application.authoring.descriptor

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * Tests for [InputSource] and its three concrete subtypes:
 * - [FileInputSource]
 * - [StepOutputInputSource]
 * - [EnvironmentVariableInputSource]
 *
 * Covers:
 * - Data class equality and copy semantics
 * - Property accessors
 * - JSON serialization: discriminator field, field names, roundtrip
 * - Negative: unknown discriminator, missing required field
 */
class InputSourceTest
{
    private val json = Json { classDiscriminator = "type" }

    // ── FileInputSource ───────────────────────────────────────────────────────

    @Test
    fun `FileInputSource stores path`()
    {
        val source = FileInputSource( path = "./data/raw_eeg.edf" )
        assertEquals( "./data/raw_eeg.edf", source.path )
    }

    @Test
    fun `FileInputSource equality and copy`()
    {
        val a = FileInputSource( path = "./a.edf" )
        val b = FileInputSource( path = "./a.edf" )
        val c = a.copy( path = "./b.edf" )

        assertEquals( a, b )
        assertNotEquals( a, c )
        assertEquals( "./b.edf", c.path )
    }

    @Test
    fun `FileInputSource serializes with type discriminator file`()
    {
        val encoded = json.encodeToString<InputSource>( FileInputSource("./data.csv") )
        assert( encoded.contains("\"type\":\"file\"") ) { "Missing type discriminator: $encoded" }
        assert( encoded.contains("\"path\":\"./data.csv\"") ) { "Missing path field: $encoded" }
    }

    @Test
    fun `FileInputSource roundtrips through JSON`()
    {
        val original = FileInputSource( path = "/mnt/data/signals.edf" )
        val encoded = json.encodeToString<InputSource>( original )
        val decoded = json.decodeFromString<InputSource>( encoded )
        assertEquals( original, decoded )
    }

    @Test
    fun `FileInputSource decoded type is FileInputSource`()
    {
        val encoded = """{"type":"file","path":"./test.csv"}"""
        assertIs<FileInputSource>( json.decodeFromString<InputSource>( encoded ) )
    }

    @Test
    fun `FileInputSource path can be empty string`()
    {
        val source = FileInputSource( path = "" )
        assertEquals( "", source.path )
        val decoded = json.decodeFromString<InputSource>(
            json.encodeToString<InputSource>( source )
        )
        assertEquals( source, decoded )
    }

    // ── StepOutputInputSource ─────────────────────────────────────────────────

    @Test
    fun `StepOutputInputSource stores stepId and outputId`()
    {
        val source = StepOutputInputSource( stepId = "preprocess", outputId = "clean-eeg" )
        assertEquals( "preprocess", source.stepId )
        assertEquals( "clean-eeg", source.outputId )
    }

    @Test
    fun `StepOutputInputSource equality and copy`()
    {
        val a = StepOutputInputSource( stepId = "s1", outputId = "o1" )
        val b = StepOutputInputSource( stepId = "s1", outputId = "o1" )
        val c = a.copy( outputId = "o2" )

        assertEquals( a, b )
        assertNotEquals( a, c )
        assertEquals( "o2", c.outputId )
        assertEquals( "s1", c.stepId )
    }

    @Test
    fun `StepOutputInputSource instances with different stepId are not equal`()
    {
        val a = StepOutputInputSource( stepId = "step-A", outputId = "out" )
        val b = StepOutputInputSource( stepId = "step-B", outputId = "out" )
        assertNotEquals( a, b )
    }

    @Test
    fun `StepOutputInputSource serializes with type discriminator step-output`()
    {
        val encoded = json.encodeToString<InputSource>(
            StepOutputInputSource( stepId = "preprocess", outputId = "cleaned" )
        )
        assert( encoded.contains("\"type\":\"step-output\"") ) { "Missing discriminator: $encoded" }
        assert( encoded.contains("\"outputId\":\"cleaned\"") ) { "Missing outputId: $encoded" }
    }

    @Test
    fun `StepOutputInputSource roundtrips through JSON`()
    {
        val original = StepOutputInputSource( stepId = "validate-input", outputId = "validated-eeg" )
        val decoded = json.decodeFromString<InputSource>( json.encodeToString<InputSource>( original ) )
        assertEquals( original, decoded )
    }

    @Test
    fun `StepOutputInputSource decoded type is StepOutputInputSource`()
    {
        val encoded = """{"type":"step-output","stepId":"stepId","outputId":"o"}"""
        assertIs<StepOutputInputSource>( json.decodeFromString<InputSource>( encoded ) )
    }

    @Test
    fun `StepOutputInputSource stepId and outputId survive roundtrip`()
    {
        val original = StepOutputInputSource( stepId = "my-step-id", outputId = "my-output-id" )
        val decoded = assertIs<StepOutputInputSource>(
            json.decodeFromString<InputSource>( json.encodeToString<InputSource>( original ) )
        )
        assertEquals( "my-step-id", decoded.stepId )
        assertEquals( "my-output-id", decoded.outputId )
    }

    // ── EnvironmentVariableInputSource ────────────────────────────────────────

    @Test
    fun `EnvironmentVariableInputSource stores variableName`()
    {
        val source = EnvironmentVariableInputSource( variableName = "EEG_DATA_PATH" )
        assertEquals( "EEG_DATA_PATH", source.variableName )
    }

    @Test
    fun `EnvironmentVariableInputSource equality and copy`()
    {
        val a = EnvironmentVariableInputSource( variableName = "VAR_A" )
        val b = EnvironmentVariableInputSource( variableName = "VAR_A" )
        val c = a.copy( variableName = "VAR_B" )

        assertEquals( a, b )
        assertNotEquals( a, c )
        assertEquals( "VAR_B", c.variableName )
    }

    @Test
    fun `EnvironmentVariableInputSource serializes with type discriminator env-var`()
    {
        val encoded = json.encodeToString<InputSource>(
            EnvironmentVariableInputSource( variableName = "MY_VAR" )
        )
        assert( encoded.contains("\"type\":\"env-var\"") ) { "Missing discriminator: $encoded" }
        assert( encoded.contains("\"variableName\":\"MY_VAR\"") ) { "Missing variableName: $encoded" }
    }

    @Test
    fun `EnvironmentVariableInputSource roundtrips through JSON`()
    {
        val original = EnvironmentVariableInputSource( variableName = "EEG_DATA_PATH" )
        val decoded = json.decodeFromString<InputSource>( json.encodeToString<InputSource>( original ) )
        assertEquals( original, decoded )
    }

    @Test
    fun `EnvironmentVariableInputSource decoded type is EnvironmentVariableInputSource`()
    {
        val encoded = """{"type":"env-var","variableName":"SOME_VAR"}"""
        assertIs<EnvironmentVariableInputSource>( json.decodeFromString<InputSource>( encoded ) )
    }

    // ── Polymorphic exhaustiveness ────────────────────────────────────────────

    @Test
    fun `all three subtypes decode to distinct types`()
    {
        val file = json.decodeFromString<InputSource>("""{"type":"file","path":"x"}""")
        val step = json.decodeFromString<InputSource>("""{"type":"step-output","outputId":"o","stepId":"s"}""")
        val envVar = json.decodeFromString<InputSource>("""{"type":"env-var","variableName":"V"}""")

        assertIs<FileInputSource>( file )
        assertIs<StepOutputInputSource>( step )
        assertIs<EnvironmentVariableInputSource>( envVar )
    }

    @Test
    fun `all three subtypes are distinct from each other`()
    {
        val file: InputSource = FileInputSource( "path" )
        val step: InputSource = StepOutputInputSource( "s", "o" )
        val envVar: InputSource = EnvironmentVariableInputSource( "V" )

        assertNotEquals( file, step )
        assertNotEquals( file, envVar )
        assertNotEquals( step, envVar )
    }

    // ── Negative: serialization errors ───────────────────────────────────────

    @Test
    fun `decoding unknown type discriminator throws SerializationException`()
    {
        assertFailsWith<SerializationException> {
            json.decodeFromString<InputSource>("""{"type":"database","host":"localhost"}""")
        }
    }

    @Test
    fun `decoding missing type discriminator throws SerializationException`()
    {
        assertFailsWith<SerializationException> {
            json.decodeFromString<InputSource>("""{"path":"./data.csv"}""")
        }
    }

    @Test
    fun `decoding FileInputSource with missing path throws SerializationException`()
    {
        assertFailsWith<SerializationException> {
            json.decodeFromString<InputSource>("""{"type":"file"}""")
        }
    }

    @Test
    fun `decoding StepOutputInputSource with missing stepId throws SerializationException`()
    {
        assertFailsWith<SerializationException> {
            json.decodeFromString<InputSource>("""{"type":"step-output","outputId":"o"}""")
        }
    }

    @Test
    fun `decoding StepOutputInputSource with missing outputId throws SerializationException`()
    {
        assertFailsWith<SerializationException> {
            json.decodeFromString<InputSource>("""{"type":"step-output","stepMetadata":"s"}""")
        }
    }

    @Test
    fun `decoding EnvironmentVariableInputSource with missing variableName throws SerializationException`()
    {
        assertFailsWith<SerializationException> {
            json.decodeFromString<InputSource>("""{"type":"env-var"}""")
        }
    }
}

