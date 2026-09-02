@file:Suppress("REDUNDANT_EXPLICIT_TYPE", "RemoveExplicitTypeArguments")

package carp.dsp.core.application.authoring.descriptor

import dk.cachet.carp.common.application.data.DataType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [DataDescriptor] and its value objects [DataField] and [OntologyRef].
 */
class DataDescriptorTest
{
    private val json = Json
    private val jsonNoDefaults = Json { explicitNulls = false }

    private val heartRate = DataType.fromString( "dk.cachet.carp.heartrate" )
    private val csvFormat = OntologyRef( "http://edamontology.org/format_3752" )
    private val hrConcept = OntologyRef( "http://purl.bioontology.org/ontology/SNOMEDCT/364075005" )

    // ── OntologyRef ───────────────────────────────────────────────────────────

    @Test
    fun `OntologyRef rejects a blank iri`()
    {
        assertFailsWith<IllegalArgumentException> { OntologyRef( "" ) }
        assertFailsWith<IllegalArgumentException> { OntologyRef( "   " ) }
    }

    @Test
    fun `OntologyRef serialises as a bare string`()
    {
        val encoded = json.encodeToString( csvFormat )
        assertEquals( "\"http://edamontology.org/format_3752\"", encoded )
        assertEquals( csvFormat, json.decodeFromString<OntologyRef>( encoded ) )
    }

    @Test
    fun `OntologyRef toString is the iri`()
    {
        assertEquals( "http://edamontology.org/format_3752", csvFormat.toString() )
    }

    // ── DataField ─────────────────────────────────────────────────────────────

    @Test
    fun `DataField defaults dataType and ontologyRef to null`()
    {
        val f = DataField( name = "value" )
        assertNull( f.dataType )
        assertNull( f.ontologyRef )
    }

    @Test
    fun `DataField serialises dataType as a bare string`()
    {
        val encoded = json.encodeToString(
            DataField( name = "heart_rate_bpm", dataType = heartRate, ontologyRef = hrConcept )
        )
        assertTrue( encoded.contains("\"name\":\"heart_rate_bpm\""), encoded )
        assertTrue( encoded.contains("\"dataType\":\"dk.cachet.carp.heartrate\""), encoded )
        assertTrue(
            encoded.contains("\"ontologyRef\":\"http://purl.bioontology.org/ontology/SNOMEDCT/364075005\""),
            encoded
        )
    }

    @Test
    fun `DataField roundtrips`()
    {
        val original = DataField( name = "steps", dataType = DataType.fromString( "dk.cachet.carp.stepcount" ) )
        assertEquals( original, json.decodeFromString<DataField>( json.encodeToString( original ) ) )
    }

    // ── Defaults and equality ─────────────────────────────────────────────────

    @Test
    fun `all fields default to null or empty`()
    {
        val d = DataDescriptor()
        assertNull( d.fileFormat )
        assertNull( d.encoding )
        assertNull( d.formatRef )
        assertTrue( d.fields.isEmpty() )
        assertNull( d.notes )
    }

    @Test
    fun `equality hashCode copy`()
    {
        val a = DataDescriptor(
            fileFormat = "csv", encoding = "UTF-8", formatRef = csvFormat,
            fields = listOf( DataField( "heart_rate_bpm", heartRate, hrConcept ) ),
            notes = "raw sensor data",
        )
        val b = a.copy()
        assertEquals( a, b )
        assertEquals( a.hashCode(), b.hashCode() )
        assertNotEquals( a, DataDescriptor() )
    }

    @Test
    fun `equals null-branch coverage`()
    {
        assertNotEquals( DataDescriptor( fileFormat = "csv" ), DataDescriptor( fileFormat = null ) )
        assertNotEquals( DataDescriptor( encoding = "utf-8" ), DataDescriptor( encoding = null ) )
        assertNotEquals( DataDescriptor( formatRef = csvFormat ), DataDescriptor( formatRef = null ) )
        assertNotEquals(
            DataDescriptor( fields = listOf( DataField( "a" ) ) ),
            DataDescriptor( fields = emptyList() )
        )
        assertNotEquals( DataDescriptor( notes = "n" ), DataDescriptor( notes = null ) )
        assertNotEquals<Any>( DataDescriptor( fileFormat = "csv" ), "string" )
    }

    @Test
    fun `copy changing one field leaves the rest`()
    {
        val original = DataDescriptor(
            fileFormat = "csv", encoding = "UTF-8", formatRef = csvFormat, notes = "n",
        )
        val copied = original.copy( fileFormat = "json" )
        assertEquals( "json", copied.fileFormat )
        assertEquals( "UTF-8", copied.encoding )
        assertEquals( csvFormat, copied.formatRef )
        assertEquals( "n", copied.notes )
        assertEquals( "csv", original.fileFormat )
    }

    // ── The two-layer split ───────────────────────────────────────────────────

    @Test
    fun `format lives on the descriptor, meaning on the field`()
    {
        val d = DataDescriptor(
            fileFormat = "csv",
            formatRef = csvFormat,
            fields = listOf( DataField( "heart_rate_bpm", heartRate, hrConcept ) ),
        )
        assertEquals( csvFormat, d.formatRef )
        assertEquals( heartRate, d.fields.single().dataType )
        assertEquals( hrConcept, d.fields.single().ontologyRef )
    }

    @Test
    fun `a file can carry several typed fields`()
    {
        val d = DataDescriptor(
            fileFormat = "csv",
            fields = listOf(
                DataField( "heart_rate_bpm", heartRate ),
                DataField( "steps", DataType.fromString( "dk.cachet.carp.stepcount" ) ),
            ),
        )
        assertEquals(
            setOf( "dk.cachet.carp.heartrate", "dk.cachet.carp.stepcount" ),
            d.fields.mapNotNull { it.dataType?.toString() }.toSet()
        )
    }

    @Test
    fun `an untyped field carries no data type`()
    {
        val d = DataDescriptor( fileFormat = "csv", fields = listOf( DataField( "note" ) ) )
        assertNull( d.fields.single().dataType )
    }

    // ── JSON serialization ────────────────────────────────────────────────────

    @Test
    fun `serialises fileFormat and formatRef with correct keys`()
    {
        val encoded = json.encodeToString( DataDescriptor( fileFormat = "csv", formatRef = csvFormat ) )
        assertTrue( encoded.contains("\"fileFormat\":\"csv\""), encoded )
        assertTrue( encoded.contains("\"formatRef\":\"http://edamontology.org/format_3752\""), encoded )
    }

    @Test
    fun `roundtrip preserves a fully populated instance`()
    {
        val original = DataDescriptor(
            fileFormat = "text/csv",
            encoding = "UTF-8",
            formatRef = csvFormat,
            fields = listOf( DataField( "heart_rate_bpm", heartRate, hrConcept ) ),
            notes = "Raw heart rate",
        )
        assertEquals( original, json.decodeFromString<DataDescriptor>( json.encodeToString( original ) ) )
    }

    @Test
    fun `roundtrip with all defaults`()
    {
        val original = DataDescriptor()
        assertEquals( original, json.decodeFromString<DataDescriptor>( json.encodeToString( original ) ) )
    }

    @Test
    fun `explicitNulls false omits absent fields`()
    {
        val encoded = jsonNoDefaults.encodeToString( DataDescriptor( fileFormat = "csv" ) )
        assertTrue( encoded.contains("\"fileFormat\":\"csv\""), encoded )
        assertTrue( !encoded.contains("\"encoding\""), encoded )
        assertTrue( !encoded.contains("\"formatRef\""), encoded )
        assertTrue( !encoded.contains("\"notes\""), encoded )
        assertEquals(
            DataDescriptor( fileFormat = "csv" ),
            jsonNoDefaults.decodeFromString<DataDescriptor>( encoded )
        )
    }
}
