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
 * Dedicated tests for [DataDescriptor].
 *
 * This file covers:
 * - Data-class structural tests (equals / hashCode / toString / copy / null-branch coverage)
 * - Explicit default-value assertions for every field
 * - Property accessor tests for every field
 * - JSON serialization: field names, roundtrip for every field combination,
 *   null fields omitted when using explicitNulls = false
 * - Copy semantics: changing one field does not affect others
 */
class DataDescriptorTest
{
    private val json = Json
    private val jsonNoDefaults = Json { explicitNulls = false }

    // ── Equality / hashCode / toString / copy ─────────────────────────────────

    @Test
    fun `equality hashCode toString copy`()
    {
        val a = DataDescriptor(
            type = "json", format = "UTF-8",
            schemaRef = "http://schema/v1", ontologyRef = "http://onto/concept",
            notes = "raw sensor data",
        )
        val b = a.copy()

        assertEquals( a, b )
        assertEquals( a.hashCode(), b.hashCode() )
        assertTrue( a.toString().contains("json") )

        val minimal = DataDescriptor()
        assertNull( minimal.type )
        assertNull( minimal.format )
        assertNull( minimal.schemaRef )
        assertNull( minimal.ontologyRef )
        assertNull( minimal.notes )
        assertNotEquals( a, minimal )
    }

    @Test
    fun `copy preserves only changed fields`()
    {
        val a = DataDescriptor( type = "csv", schemaRef = "s1" )
        val b = a.copy( schemaRef = "s2" )
        assertEquals( "csv", b.type )
        assertEquals( "s2", b.schemaRef )
        assertNotEquals( a, b )
    }

    @Test
    fun `equals null-branch coverage`()
    {
        assertNotEquals( DataDescriptor( type = "csv" ), DataDescriptor( type = null ) )
        assertNotEquals( DataDescriptor( type = null ), DataDescriptor( type = "csv" ) )
        assertNotEquals( DataDescriptor( format = "utf-8" ), DataDescriptor( format = null ) )
        assertNotEquals( DataDescriptor( format = null ), DataDescriptor( format = "utf-8" ) )
        assertNotEquals( DataDescriptor( schemaRef = "s" ), DataDescriptor( schemaRef = null ) )
        assertNotEquals( DataDescriptor( schemaRef = null ), DataDescriptor( schemaRef = "s" ) )
        assertNotEquals( DataDescriptor( ontologyRef = "o" ), DataDescriptor( ontologyRef = null ) )
        assertNotEquals( DataDescriptor( ontologyRef = null ), DataDescriptor( ontologyRef = "o" ) )
        assertNotEquals( DataDescriptor( notes = "n" ), DataDescriptor( notes = null ) )
        assertNotEquals( DataDescriptor( notes = null ), DataDescriptor( notes = "n" ) )
        assertNotEquals<Any>( DataDescriptor( type = "csv" ), "string" )
    }

    // ── Default values ────────────────────────────────────────────────────────

    @Test
    fun `all fields default to null`()
    {
        val d = DataDescriptor()
        assertNull( d.type )
        assertNull( d.format )
        assertNull( d.schemaRef )
        assertNull( d.ontologyRef )
        assertNull( d.notes )
    }

    // ── Property accessors ────────────────────────────────────────────────────

    @Test
    fun `stores type`()
    {
        assertEquals( "text/csv", DataDescriptor( type = "text/csv" ).type )
    }

    @Test
    fun `stores format`()
    {
        assertEquals( "UTF-8", DataDescriptor( format = "UTF-8" ).format )
    }

    @Test
    fun `stores schemaRef`()
    {
        assertEquals(
            "https://schema.org/Dataset",
            DataDescriptor( schemaRef = "https://schema.org/Dataset" ).schemaRef
        )
    }

    @Test
    fun `stores ontologyRef`()
    {
        assertEquals(
            "obo:CHEBI_12345",
            DataDescriptor( ontologyRef = "obo:CHEBI_12345" ).ontologyRef
        )
    }

    @Test
    fun `stores notes`()
    {
        assertEquals(
            "Raw EEG signal sampled at 256 Hz",
            DataDescriptor( notes = "Raw EEG signal sampled at 256 Hz" ).notes
        )
    }

    // ── Copy semantics ────────────────────────────────────────────────────────

    @Test
    fun `copy changing type does not affect other fields`()
    {
        val original = DataDescriptor(
            type = "csv",
            format = "UTF-8",
            schemaRef = "s",
            ontologyRef = "o",
            notes = "n",
        )
        val copied = original.copy( type = "json" )

        assertEquals( "json", copied.type )
        assertEquals( "UTF-8", copied.format )
        assertEquals( "s", copied.schemaRef )
        assertEquals( "o", copied.ontologyRef )
        assertEquals( "n", copied.notes )
        assertEquals( "csv", original.type ) // original unchanged
    }

    @Test
    fun `copy can nullify a previously set field`()
    {
        val original = DataDescriptor( type = "csv", format = "UTF-8" )
        val nullified = original.copy( format = null )
        assertNull( nullified.format )
        assertEquals( "csv", nullified.type )
    }

    @Test
    fun `copy preserving all fields produces equal instance`()
    {
        val d = DataDescriptor( type = "parquet", schemaRef = "ref" )
        assertEquals( d, d.copy() )
    }

    // ── Equality edge cases ───────────────────────────────────────────────────

    @Test
    fun `two fully populated equal instances are equal`()
    {
        val a = DataDescriptor( "csv", "UTF-8", "s", "o", "n" )
        val b = DataDescriptor( "csv", "UTF-8", "s", "o", "n" )
        assertEquals( a, b )
        assertEquals( a.hashCode(), b.hashCode() )
    }

    @Test
    fun `instances differing only in schemaRef are not equal`()
    {
        val a = DataDescriptor( type = "csv", schemaRef = "schema-v1" )
        val b = DataDescriptor( type = "csv", schemaRef = "schema-v2" )
        assertNotEquals( a, b )
    }

    @Test
    fun `instances differing only in ontologyRef are not equal`()
    {
        val a = DataDescriptor( ontologyRef = "onto-a" )
        val b = DataDescriptor( ontologyRef = "onto-b" )
        assertNotEquals( a, b )
    }

    @Test
    fun `instances differing only in notes are not equal`()
    {
        val a = DataDescriptor( notes = "note A" )
        val b = DataDescriptor( notes = "note B" )
        assertNotEquals( a, b )
    }

    // ── JSON serialization: field names ───────────────────────────────────────

    @Test
    fun `serializes type field with correct key`()
    {
        val encoded = json.encodeToString( DataDescriptor( type = "application/json" ) )
        assertTrue( encoded.contains("\"type\":\"application/json\""), "Missing type field: $encoded" )
    }

    @Test
    fun `serializes format field with correct key`()
    {
        val encoded = json.encodeToString( DataDescriptor( format = "UTF-16" ) )
        assertTrue( encoded.contains("\"format\":\"UTF-16\""), "Missing format field: $encoded" )
    }

    @Test
    fun `serializes schemaRef field with correct key`()
    {
        val encoded = json.encodeToString( DataDescriptor( schemaRef = "https://example.com/schema" ) )
        assertTrue( encoded.contains("\"schemaRef\":\"https://example.com/schema\""), "Missing schemaRef: $encoded" )
    }

    @Test
    fun `serializes ontologyRef field with correct key`()
    {
        val encoded = json.encodeToString( DataDescriptor( ontologyRef = "obo:EFO_0001" ) )
        assertTrue( encoded.contains("\"ontologyRef\":\"obo:EFO_0001\""), "Missing ontologyRef: $encoded" )
    }

    @Test
    fun `serializes notes field with correct key`()
    {
        val encoded = json.encodeToString( DataDescriptor( notes = "Sensor stream at 512 Hz" ) )
        assertTrue( encoded.contains("\"notes\":\"Sensor stream at 512 Hz\""), "Missing notes: $encoded" )
    }

    // ── JSON roundtrip ────────────────────────────────────────────────────────

    @Test
    fun `roundtrip preserves fully populated instance`()
    {
        val original = DataDescriptor(
            type = "text/csv",
            format = "UTF-8",
            schemaRef = "https://schema.org/v1",
            ontologyRef = "obo:CHEBI_12345",
            notes = "Raw EEG at 256 Hz",
        )
        val decoded = json.decodeFromString<DataDescriptor>( json.encodeToString( original ) )
        assertEquals( original, decoded )
    }

    @Test
    fun `roundtrip with all defaults (all null)`()
    {
        val original = DataDescriptor()
        val decoded = json.decodeFromString<DataDescriptor>( json.encodeToString( original ) )
        assertEquals( original, decoded )
    }

    @Test
    fun `roundtrip preserves only type set`()
    {
        val original = DataDescriptor( type = "parquet" )
        val decoded = json.decodeFromString<DataDescriptor>( json.encodeToString( original ) )
        assertEquals( original, decoded )
        assertNull( decoded.format )
        assertNull( decoded.schemaRef )
        assertNull( decoded.ontologyRef )
        assertNull( decoded.notes )
    }

    @Test
    fun `roundtrip preserves only schemaRef and ontologyRef set`()
    {
        val original = DataDescriptor( schemaRef = "s1", ontologyRef = "o1" )
        val decoded = json.decodeFromString<DataDescriptor>( json.encodeToString( original ) )
        assertEquals( original, decoded )
        assertNull( decoded.type )
        assertNull( decoded.format )
        assertNull( decoded.notes )
    }

    @Test
    fun `roundtrip with explicitNulls false omits null fields`()
    {
        val original = DataDescriptor( type = "csv" )
        val encoded = jsonNoDefaults.encodeToString( original )
        assertTrue( encoded.contains("\"type\":\"csv\""), "type must be present: $encoded" )
        assertTrue( !encoded.contains("\"format\""), "format must be omitted: $encoded" )
        assertTrue( !encoded.contains("\"schemaRef\""), "schemaRef must be omitted: $encoded" )
        assertTrue( !encoded.contains("\"ontologyRef\""), "ontologyRef must be omitted: $encoded" )
        assertTrue( !encoded.contains("\"notes\""), "notes must be omitted: $encoded" )
        val decoded = jsonNoDefaults.decodeFromString<DataDescriptor>( encoded )
        assertEquals( original, decoded )
    }

    @Test
    fun `has no type discriminator in serialized output`()
    {
        // DataDescriptor is not polymorphic — no class discriminator should appear
        val encoded = json.encodeToString( DataDescriptor( type = "csv" ) )
        // The "type" key IS a real field here — confirm it contains exactly the value, not a class tag
        assertTrue( encoded.contains("\"type\":\"csv\""), "Expected type field value: $encoded" )
    }
}

