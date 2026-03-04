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
 * Dedicated tests for [StepMetadataDescriptor].
 *
 * This file covers:
 * - Data-class structural tests (equals / hashCode / toString / copy / null-branch coverage)
 * - Explicit default-value assertions
 * - Tags list behaviour
 * - JSON serialization: field names, discriminator absence, roundtrip, defaults not emitted
 */
class StepMetadataDescriptorTest
{

    // ── Equality / hashCode / toString / copy ─────────────────────────────────

    @Test
    fun `equality hashCode toString copy`()
    {
        val a = StepMetadataDescriptor( name = "step-1", version = "2.0", tags = listOf("etl") )
        val b = a.copy()

        assertEquals( a, b )
        assertEquals( a.hashCode(), b.hashCode() )
        assertTrue( a.toString().contains("step-1") )
        assertNotEquals( a, a.copy( name = "step-2" ) )

        val defaults = StepMetadataDescriptor()
        assertNull( defaults.name )
        assertNull( defaults.description )
        assertEquals( "1.0", defaults.version )
        assertTrue( defaults.tags.isEmpty() )
    }


    @Test
    fun `equals null-branch coverage`()
    {
        assertNotEquals( StepMetadataDescriptor( name = "s" ), StepMetadataDescriptor( name = null ) )
        assertNotEquals( StepMetadataDescriptor( name = null ), StepMetadataDescriptor( name = "s" ) )
        assertNotEquals( StepMetadataDescriptor( description = "d" ), StepMetadataDescriptor( description = null ) )
        assertNotEquals( StepMetadataDescriptor( description = null ), StepMetadataDescriptor( description = "d" ) )
        assertNotEquals<Any>( StepMetadataDescriptor( name = "s" ), "string" )
    }

    // ── Default values ────────────────────────────────────────────────────────

    @Test
    fun `default name is null`()
    {
        assertNull( StepMetadataDescriptor().name )
    }

    @Test
    fun `default description is null`()
    {
        assertNull( StepMetadataDescriptor().description )
    }

    @Test
    fun `default version is 1_0`()
    {
        assertEquals( "1.0", StepMetadataDescriptor().version )
    }

    @Test
    fun `default tags is empty list`()
    {
        assertTrue( StepMetadataDescriptor().tags.isEmpty() )
    }

    // ── Property accessors ────────────────────────────────────────────────────

    @Test
    fun `stores name`()
    {
        assertEquals( "Preprocess EEG", StepMetadataDescriptor( name = "Preprocess EEG" ).name )
    }

    @Test
    fun `stores description`()
    {
        assertEquals( "Applies band-pass filter", StepMetadataDescriptor( description = "Applies band-pass filter" ).description )
    }

    @Test
    fun `stores version`()
    {
        assertEquals( "3.2", StepMetadataDescriptor( version = "3.2" ).version )
    }

    @Test
    fun `stores tags list`()
    {
        val tags = listOf("eeg", "preprocessing", "filter")
        assertEquals( tags, StepMetadataDescriptor( tags = tags ).tags )
    }

    // ── Tags list behaviour ───────────────────────────────────────────────────

    @Test
    fun `tags with different contents are not equal`()
    {
        val a = StepMetadataDescriptor( tags = listOf("eeg") )
        val b = StepMetadataDescriptor( tags = listOf("emg") )
        assertNotEquals( a, b )
    }

    @Test
    fun `tags order matters for equality`()
    {
        val a = StepMetadataDescriptor( tags = listOf("a", "b") )
        val b = StepMetadataDescriptor( tags = listOf("b", "a") )
        assertNotEquals( a, b )
    }

    @Test
    fun `empty tags list equals another empty tags list`()
    {
        assertEquals(
            StepMetadataDescriptor( tags = emptyList() ),
            StepMetadataDescriptor( tags = emptyList() )
        )
    }

    @Test
    fun `copy preserves tags when not overridden`()
    {
        val original = StepMetadataDescriptor( name = "Step", tags = listOf("etl", "prod") )
        val copied = original.copy( name = "Step Copy" )
        assertEquals( listOf("etl", "prod"), copied.tags )
    }

    @Test
    fun `copy can replace tags`()
    {
        val original = StepMetadataDescriptor( tags = listOf("old") )
        val updated = original.copy( tags = listOf("new-a", "new-b") )
        assertEquals( listOf("new-a", "new-b"), updated.tags )
        assertEquals( listOf("old"), original.tags )
    }

    // ── JSON serialization ────────────────────────────────────────────────────

    @Test
    fun `serializes name field`()
    {
        val encoded = Json.encodeToString( StepMetadataDescriptor( name = "Validate Input" ) )
        assertTrue( encoded.contains("\"name\":\"Validate Input\""), "Missing name field: $encoded" )
    }

    @Test
    fun `serializes description field`()
    {
        val encoded = Json.encodeToString( StepMetadataDescriptor( description = "Checks raw EEG" ) )
        assertTrue( encoded.contains("\"description\":\"Checks raw EEG\""), "Missing description field: $encoded" )
    }

    @Test
    fun `serializes version field`()
    {
        val encoded = Json.encodeToString( StepMetadataDescriptor( version = "2.0" ) )
        assertTrue( encoded.contains("\"version\":\"2.0\""), "Missing version field: $encoded" )
    }

    @Test
    fun `serializes tags field`()
    {
        val encoded = Json.encodeToString( StepMetadataDescriptor( tags = listOf("eeg", "filter") ) )
        assertTrue( encoded.contains("\"tags\""), "Missing tags field: $encoded" )
        assertTrue( encoded.contains("\"eeg\""), "Missing tag value: $encoded" )
        assertTrue( encoded.contains("\"filter\""), "Missing tag value: $encoded" )
    }

    @Test
    fun `roundtrip preserves all fields`()
    {
        val original = StepMetadataDescriptor(
            name = "Extract Features",
            description = "Computes spectral features",
            version = "1.3",
            tags = listOf("features", "spectral"),
        )
        val decoded = Json.decodeFromString<StepMetadataDescriptor>( Json.encodeToString( original ) )
        assertEquals( original, decoded )
    }

    @Test
    fun `roundtrip with all defaults`()
    {
        val original = StepMetadataDescriptor()
        val decoded = Json.decodeFromString<StepMetadataDescriptor>( Json.encodeToString( original ) )
        assertEquals( original, decoded )
    }

    @Test
    fun `roundtrip preserves null name`()
    {
        val original = StepMetadataDescriptor( name = null, description = "desc" )
        val decoded = Json.decodeFromString<StepMetadataDescriptor>( Json.encodeToString( original ) )
        assertNull( decoded.name )
        assertEquals( "desc", decoded.description )
    }

    @Test
    fun `roundtrip preserves empty tags`()
    {
        val original = StepMetadataDescriptor( name = "s", tags = emptyList() )
        val decoded = Json.decodeFromString<StepMetadataDescriptor>( Json.encodeToString( original ) )
        assertTrue( decoded.tags.isEmpty() )
    }

    @Test
    fun `roundtrip preserves multi-element tags`()
    {
        val tags = listOf("eeg", "preprocessing", "mne", "python")
        val original = StepMetadataDescriptor( tags = tags )
        val decoded = Json.decodeFromString<StepMetadataDescriptor>( Json.encodeToString( original ) )
        assertEquals( tags, decoded.tags )
    }

    @Test
    fun `has no type discriminator in serialized output`()
    {
        // StepMetadataDescriptor is not polymorphic — no "type" field should appear
        val encoded = Json.encodeToString( StepMetadataDescriptor( name = "s" ) )
        assertTrue( !encoded.contains("\"type\""), "Unexpected type discriminator: $encoded" )
    }
}

