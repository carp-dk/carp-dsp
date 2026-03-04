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
 * Tests for [OutputDestination] and its two concrete subtypes:
 * - [FileOutputDestination]
 * - [EnvironmentVariableOutputDestination]
 *
 * Covers:
 * - Data class equality and copy semantics
 * - Property accessors
 * - JSON serialization: discriminator field, field names, roundtrip
 * - Negative: unknown discriminator, missing required field
 */
class OutputDestinationTest
{
    private val json = Json { classDiscriminator = "type" }

    // ── FileOutputDestination ─────────────────────────────────────────────────

    @Test
    fun `FileOutputDestination stores path`()
    {
        val dest = FileOutputDestination( path = "/workspace/clean.edf" )
        assertEquals( "/workspace/clean.edf", dest.path )
    }

    @Test
    fun `FileOutputDestination equality and copy`()
    {
        val a = FileOutputDestination( path = "./out/a.csv" )
        val b = FileOutputDestination( path = "./out/a.csv" )
        val c = a.copy( path = "./out/b.csv" )

        assertEquals( a, b )
        assertNotEquals( a, c )
        assertEquals( "./out/b.csv", c.path )
    }

    @Test
    fun `FileOutputDestination path can be empty string`()
    {
        val dest = FileOutputDestination( path = "" )
        assertEquals( "", dest.path )
        val decoded = json.decodeFromString<OutputDestination>(
            json.encodeToString<OutputDestination>( dest )
        )
        assertEquals( dest, decoded )
    }

    @Test
    fun `FileOutputDestination serializes with type discriminator file`()
    {
        val encoded = json.encodeToString<OutputDestination>( FileOutputDestination( "/out/data.csv" ) )
        assert( encoded.contains("\"type\":\"file\"") ) { "Missing discriminator: $encoded" }
        assert( encoded.contains("\"path\":\"/out/data.csv\"") ) { "Missing path: $encoded" }
    }

    @Test
    fun `FileOutputDestination roundtrips through JSON`()
    {
        val original = FileOutputDestination( path = "/mnt/results/features.parquet" )
        val decoded = json.decodeFromString<OutputDestination>( json.encodeToString<OutputDestination>( original ) )
        assertEquals( original, decoded )
    }

    @Test
    fun `FileOutputDestination decoded type is FileOutputDestination`()
    {
        val encoded = """{"type":"file","path":"./result.csv"}"""
        assertIs<FileOutputDestination>( json.decodeFromString<OutputDestination>( encoded ) )
    }

    @Test
    fun `FileOutputDestination path survives roundtrip`()
    {
        val original = FileOutputDestination( path = "/deep/nested/path/output.json" )
        val decoded = assertIs<FileOutputDestination>(
            json.decodeFromString<OutputDestination>( json.encodeToString<OutputDestination>( original ) )
        )
        assertEquals( original.path, decoded.path )
    }

    // ── EnvironmentVariableOutputDestination ──────────────────────────────────

    @Test
    fun `EnvironmentVariableOutputDestination stores variableName`()
    {
        val dest = EnvironmentVariableOutputDestination( variableName = "CLEAN_EEG_PATH" )
        assertEquals( "CLEAN_EEG_PATH", dest.variableName )
    }

    @Test
    fun `EnvironmentVariableOutputDestination equality and copy`()
    {
        val a = EnvironmentVariableOutputDestination( variableName = "VAR_A" )
        val b = EnvironmentVariableOutputDestination( variableName = "VAR_A" )
        val c = a.copy( variableName = "VAR_B" )

        assertEquals( a, b )
        assertNotEquals( a, c )
        assertEquals( "VAR_B", c.variableName )
    }

    @Test
    fun `EnvironmentVariableOutputDestination serializes with type discriminator env-var`()
    {
        val encoded = json.encodeToString<OutputDestination>(
            EnvironmentVariableOutputDestination( variableName = "OUT_PATH" )
        )
        assert( encoded.contains("\"type\":\"env-var\"") ) { "Missing discriminator: $encoded" }
        assert( encoded.contains("\"variableName\":\"OUT_PATH\"") ) { "Missing variableName: $encoded" }
    }

    @Test
    fun `EnvironmentVariableOutputDestination roundtrips through JSON`()
    {
        val original = EnvironmentVariableOutputDestination( variableName = "RESULT_CSV_PATH" )
        val decoded = json.decodeFromString<OutputDestination>( json.encodeToString<OutputDestination>( original ) )
        assertEquals( original, decoded )
    }

    @Test
    fun `EnvironmentVariableOutputDestination decoded type is correct`()
    {
        val encoded = """{"type":"env-var","variableName":"SOME_VAR"}"""
        assertIs<EnvironmentVariableOutputDestination>( json.decodeFromString<OutputDestination>( encoded ) )
    }

    @Test
    fun `EnvironmentVariableOutputDestination variableName survives roundtrip`()
    {
        val original = EnvironmentVariableOutputDestination( variableName = "MY_OUTPUT_KEY" )
        val decoded = assertIs<EnvironmentVariableOutputDestination>(
            json.decodeFromString<OutputDestination>( json.encodeToString<OutputDestination>( original ) )
        )
        assertEquals( original.variableName, decoded.variableName )
    }

    // ── Polymorphic exhaustiveness ────────────────────────────────────────────

    @Test
    fun `both subtypes decode to distinct types`()
    {
        val file = json.decodeFromString<OutputDestination>("""{"type":"file","path":"x"}""")
        val envVar = json.decodeFromString<OutputDestination>("""{"type":"env-var","variableName":"V"}""")

        assertIs<FileOutputDestination>( file )
        assertIs<EnvironmentVariableOutputDestination>( envVar )
    }

    @Test
    fun `both subtypes are mutually not equal`()
    {
        val file: OutputDestination = FileOutputDestination( "path" )
        val envVar: OutputDestination = EnvironmentVariableOutputDestination( "VAR" )
        assertNotEquals( file, envVar )
    }

    @Test
    fun `two FileOutputDestination instances with different paths are not equal`()
    {
        assertNotEquals(
            FileOutputDestination( "/out/a.csv" ),
            FileOutputDestination( "/out/b.csv" )
        )
    }

    @Test
    fun `two EnvironmentVariableOutputDestination instances with different names are not equal`()
    {
        assertNotEquals(
            EnvironmentVariableOutputDestination( "VAR_A" ),
            EnvironmentVariableOutputDestination( "VAR_B" )
        )
    }

    // ── Negative: serialization errors ───────────────────────────────────────

    @Test
    fun `decoding unknown type discriminator throws SerializationException`()
    {
        assertFailsWith<SerializationException> {
            json.decodeFromString<OutputDestination>("""{"type":"database","table":"results"}""")
        }
    }

    @Test
    fun `decoding missing type discriminator throws SerializationException`()
    {
        assertFailsWith<SerializationException> {
            json.decodeFromString<OutputDestination>("""{"path":"./out.csv"}""")
        }
    }

    @Test
    fun `decoding FileOutputDestination with missing path throws SerializationException`()
    {
        assertFailsWith<SerializationException> {
            json.decodeFromString<OutputDestination>("""{"type":"file"}""")
        }
    }

    @Test
    fun `decoding EnvironmentVariableOutputDestination with missing variableName throws SerializationException`()
    {
        assertFailsWith<SerializationException> {
            json.decodeFromString<OutputDestination>("""{"type":"env-var"}""")
        }
    }
}

