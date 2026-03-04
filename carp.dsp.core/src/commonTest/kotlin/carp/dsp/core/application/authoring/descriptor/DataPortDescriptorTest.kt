@file:Suppress("REDUNDANT_EXPLICIT_TYPE", "RemoveExplicitTypeArguments")

package carp.dsp.core.application.authoring.descriptor

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Dedicated tests for [DataPortDescriptor].
 *
 * Migrated structural tests (equals / hashCode / toString / copy / null-branch coverage)
 * previously in [DescriptorDataClassTest] are included here alongside:
 * - Explicit default-value assertions
 * - Property accessor tests for every field
 * - Copy semantics
 * - JSON serialization: field names, roundtrip for every field combination,
 *   null fields omitted with explicitNulls = false,
 *   polymorphic source / destination roundtrip
 */
class DataPortDescriptorTest
{
    private val json = Json
    private val jsonNoDefaults = Json { explicitNulls = false }

    // ── Structural: equality / hashCode / toString / copy ─────────────────────

    @Test
    fun `equality hashCode toString copy`()
    {
        val desc = DataDescriptor( type = "csv", format = "UTF-8" )
        val a = DataPortDescriptor( id = "p-1", descriptor = desc )
        val b = a.copy()

        assertEquals( a, b )
        assertEquals( a.hashCode(), b.hashCode() )
        assertTrue( a.toString().contains("p-1") )

        val noId = a.copy( id = null, descriptor = null )
        assertNull( noId.id )
        assertNull( noId.descriptor )
        assertNotEquals( a, noId )
    }

    @Test
    fun `equals null-branch coverage`()
    {
        assertNotEquals( DataPortDescriptor( id = "x" ), DataPortDescriptor( id = null ) )
        assertNotEquals( DataPortDescriptor( id = null ), DataPortDescriptor( id = "x" ) )
        assertNotEquals( DataPortDescriptor( descriptor = DataDescriptor( type = "csv" ) ), DataPortDescriptor( descriptor = null ) )
        assertNotEquals( DataPortDescriptor( descriptor = null ), DataPortDescriptor( descriptor = DataDescriptor( type = "csv" ) ) )
        assertNotEquals( DataPortDescriptor( source = FileInputSource("p") ), DataPortDescriptor( source = null ) )
        assertNotEquals( DataPortDescriptor( source = null ), DataPortDescriptor( source = FileInputSource("p") ) )
        assertNotEquals( DataPortDescriptor( destination = FileOutputDestination("p") ), DataPortDescriptor( destination = null ) )
        assertNotEquals( DataPortDescriptor( destination = null ), DataPortDescriptor( destination = FileOutputDestination("p") ) )
        assertNotEquals<Any>( DataPortDescriptor( id = "x" ), "string" )
    }

    // ── Default values ────────────────────────────────────────────────────────

    @Test
    fun `all fields default to null`()
    {
        val d = DataPortDescriptor()
        assertNull( d.id )
        assertNull( d.descriptor )
        assertNull( d.source )
        assertNull( d.destination )
    }

    // ── Property accessors ────────────────────────────────────────────────────

    @Test
    fun `stores id`()
    {
        assertEquals( "port-abc", DataPortDescriptor( id = "port-abc" ).id )
    }

    @Test
    fun `stores descriptor`()
    {
        val desc = DataDescriptor( type = "parquet", format = "UTF-8" )
        assertEquals( desc, DataPortDescriptor( descriptor = desc ).descriptor )
    }

    @Test
    fun `stores FileInputSource as source`()
    {
        val source = FileInputSource( "./data/raw.edf" )
        assertEquals( source, DataPortDescriptor( source = source ).source )
    }

    @Test
    fun `stores StepOutputInputSource as source`()
    {
        val source = StepOutputInputSource( stepId = "preprocess", outputId = "clean-eeg" )
        assertEquals( source, DataPortDescriptor( source = source ).source )
    }

    @Test
    fun `stores EnvironmentVariableInputSource as source`()
    {
        val source = EnvironmentVariableInputSource( variableName = "EEG_DATA_PATH" )
        assertEquals( source, DataPortDescriptor( source = source ).source )
    }

    @Test
    fun `stores FileOutputDestination as destination`()
    {
        val dest = FileOutputDestination( "/workspace/validated.edf" )
        assertEquals( dest, DataPortDescriptor( destination = dest ).destination )
    }

    @Test
    fun `stores EnvironmentVariableOutputDestination as destination`()
    {
        val dest = EnvironmentVariableOutputDestination( variableName = "CLEAN_EEG_PATH" )
        assertEquals( dest, DataPortDescriptor( destination = dest ).destination )
    }

    // ── Copy semantics ────────────────────────────────────────────────────────

    @Test
    fun `copy changing id does not affect other fields`()
    {
        val desc = DataDescriptor( type = "csv" )
        val source = FileInputSource( "./in.csv" )
        val dest = FileOutputDestination( "./out.csv" )
        val orig = DataPortDescriptor( id = "old", descriptor = desc, source = source, destination = dest )
        val copied = orig.copy( id = "new" )

        assertEquals( "new", copied.id )
        assertEquals( desc, copied.descriptor )
        assertEquals( source, copied.source )
        assertEquals( dest, copied.destination )
        assertEquals( "old", orig.id ) // original unchanged
    }

    @Test
    fun `copy can nullify source`()
    {
        val orig = DataPortDescriptor( source = FileInputSource("./in.csv") )
        val nullified = orig.copy( source = null )
        assertNull( nullified.source )
    }

    @Test
    fun `copy can nullify destination`()
    {
        val orig = DataPortDescriptor( destination = FileOutputDestination("./out.csv") )
        val nullified = orig.copy( destination = null )
        assertNull( nullified.destination )
    }

    // ── JSON: field names ─────────────────────────────────────────────────────

    @Test
    fun `serializes id field`()
    {
        val encoded = json.encodeToString( DataPortDescriptor( id = "port-1" ) )
        assertTrue( encoded.contains("\"id\":\"port-1\""), "Missing id: $encoded" )
    }

    @Test
    fun `serializes descriptor field`()
    {
        val encoded = json.encodeToString( DataPortDescriptor( descriptor = DataDescriptor( type = "csv" ) ) )
        assertTrue( encoded.contains("\"descriptor\""), "Missing descriptor key: $encoded" )
        assertTrue( encoded.contains("\"csv\""), "Missing type value: $encoded" )
    }

    @Test
    fun `serializes source field with type discriminator`()
    {
        val encoded = json.encodeToString( DataPortDescriptor( source = FileInputSource("./in.csv") ) )
        assertTrue( encoded.contains("\"source\""), "Missing source key: $encoded" )
        assertTrue( encoded.contains("\"type\":\"file\""), "Missing type discriminator: $encoded" )
        assertTrue( encoded.contains("\"./in.csv\""), "Missing path value: $encoded" )
    }

    @Test
    fun `serializes destination field with type discriminator`()
    {
        val encoded = json.encodeToString( DataPortDescriptor( destination = FileOutputDestination("./out.csv") ) )
        assertTrue( encoded.contains("\"destination\""), "Missing destination key: $encoded" )
        assertTrue( encoded.contains("\"type\":\"file\""), "Missing type discriminator: $encoded" )
        assertTrue( encoded.contains("\"./out.csv\""), "Missing path value: $encoded" )
    }

    // ── JSON: roundtrip ───────────────────────────────────────────────────────

    @Test
    fun `roundtrip with all fields null`()
    {
        val original = DataPortDescriptor()
        assertEquals( original, json.decodeFromString<DataPortDescriptor>( json.encodeToString( original ) ) )
    }

    @Test
    fun `roundtrip with id and descriptor only`()
    {
        val original = DataPortDescriptor( id = "p-1", descriptor = DataDescriptor( type = "csv", format = "UTF-8" ) )
        assertEquals( original, json.decodeFromString<DataPortDescriptor>( json.encodeToString( original ) ) )
    }

    @Test
    fun `roundtrip with FileInputSource`()
    {
        val original = DataPortDescriptor( source = FileInputSource("./raw.edf") )
        assertEquals( original, json.decodeFromString<DataPortDescriptor>( json.encodeToString( original ) ) )
    }

    @Test
    fun `roundtrip with StepOutputInputSource`()
    {
        val original = DataPortDescriptor( source = StepOutputInputSource("step-1", "output-1") )
        assertEquals( original, json.decodeFromString<DataPortDescriptor>( json.encodeToString( original ) ) )
    }

    @Test
    fun `roundtrip with EnvironmentVariableInputSource`()
    {
        val original = DataPortDescriptor( source = EnvironmentVariableInputSource("MY_VAR") )
        assertEquals( original, json.decodeFromString<DataPortDescriptor>( json.encodeToString( original ) ) )
    }

    @Test
    fun `roundtrip with FileOutputDestination`()
    {
        val original = DataPortDescriptor( destination = FileOutputDestination("/workspace/out.csv") )
        assertEquals( original, json.decodeFromString<DataPortDescriptor>( json.encodeToString( original ) ) )
    }

    @Test
    fun `roundtrip with EnvironmentVariableOutputDestination`()
    {
        val original = DataPortDescriptor( destination = EnvironmentVariableOutputDestination("OUT_PATH") )
        assertEquals( original, json.decodeFromString<DataPortDescriptor>( json.encodeToString( original ) ) )
    }

    @Test
    fun `roundtrip fully populated`()
    {
        val original = DataPortDescriptor(
            id = "port-99",
            descriptor = DataDescriptor( type = "parquet", format = "UTF-8", notes = "features" ),
            source = StepOutputInputSource( stepId = "preprocess", outputId = "clean" ),
            destination = FileOutputDestination( "/results/features.parquet" ),
        )
        assertEquals( original, json.decodeFromString<DataPortDescriptor>( json.encodeToString( original ) ) )
    }

    @Test
    fun `roundtrip with explicitNulls false omits null fields`()
    {
        val original = DataPortDescriptor( id = "p-1" )
        val encoded = jsonNoDefaults.encodeToString( original )
        assertTrue( encoded.contains("\"id\":\"p-1\""), "id must be present: $encoded" )
        assertTrue( !encoded.contains("\"descriptor\""), "descriptor must be omitted: $encoded" )
        assertTrue( !encoded.contains("\"source\""), "source must be omitted: $encoded" )
        assertTrue( !encoded.contains("\"destination\""), "destination must be omitted: $encoded" )
        assertEquals( original, jsonNoDefaults.decodeFromString<DataPortDescriptor>( encoded ) )
    }
}

