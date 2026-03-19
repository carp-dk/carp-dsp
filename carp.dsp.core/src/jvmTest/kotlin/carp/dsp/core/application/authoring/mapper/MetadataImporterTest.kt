package carp.dsp.core.application.authoring.mapper

import carp.dsp.core.application.authoring.descriptor.StepMetadataDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowMetadataDescriptor
import dk.cachet.carp.analytics.domain.workflow.Version
import dk.cachet.carp.common.application.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MetadataImporterTest
{
    private val namespace = UUID( "6ba7b810-9dad-11d1-80b4-00c04fd430c8" )

    // ── parseVersion

    @Test
    fun `parseVersion parses major-only string`()
    {
        assertEquals( Version(1), MetadataImporter.parseVersion("1") )
        assertEquals( Version(2), MetadataImporter.parseVersion("2") )
    }

    @Test
    fun `parseVersion parses major-dot-minor string`()
    {
        assertEquals( Version(1, 0), MetadataImporter.parseVersion("1.0") )
        assertEquals( Version(2, 3), MetadataImporter.parseVersion("2.3") )
        assertEquals( Version(3, 11), MetadataImporter.parseVersion("3.11") )
    }

    @Test
    fun `parseVersion falls back to Version(1) for empty string`()
    {
        assertEquals( Version(1), MetadataImporter.parseVersion("") )
    }

    @Test
    fun `parseVersion falls back to Version(1) for non-numeric string`()
    {
        assertEquals( Version(1), MetadataImporter.parseVersion("not-a-version") )
        assertEquals( Version(1), MetadataImporter.parseVersion("x.y") )
    }

    @Test
    fun `parseVersion falls back to Version(1) for whitespace-only string`()
    {
        assertEquals( Version(1), MetadataImporter.parseVersion("   ") )
    }

    @Test
    fun `parseVersion ignores extra dot-separated segments`()
    {
        // Only major.minor is modelled — a third segment is silently ignored
        val result = MetadataImporter.parseVersion("1.2.3")
        assertEquals( Version(1, 2), result )
    }

    // ── importWorkflowMetadata: explicit id ───────────────────────────────────

    @Test
    fun `importWorkflowMetadata preserves explicit UUID id`()
    {
        val wfId = UUID.randomUUID()
        val d = WorkflowMetadataDescriptor( id = wfId.toString(), name = "WF" )

        val result = MetadataImporter.importWorkflowMetadata( d, namespace )
        assertEquals( wfId, result.id )
    }

    @Test
    fun `importWorkflowMetadata preserves name`()
    {
        val d = WorkflowMetadataDescriptor( id = UUID.randomUUID().toString(), name = "My Workflow" )
        assertEquals( "My Workflow", MetadataImporter.importWorkflowMetadata( d, namespace ).name )
    }

    @Test
    fun `importWorkflowMetadata preserves description`()
    {
        val d = WorkflowMetadataDescriptor(
            id = UUID.randomUUID().toString(), name = "WF", description = "A description"
        )
        assertEquals( "A description", MetadataImporter.importWorkflowMetadata( d, namespace ).description )
    }

    @Test
    fun `importWorkflowMetadata maps null description to null`()
    {
        val d = WorkflowMetadataDescriptor( id = UUID.randomUUID().toString(), name = "WF" )
        assertNull( MetadataImporter.importWorkflowMetadata( d, namespace ).description )
    }

    @Test
    fun `importWorkflowMetadata parses version string`()
    {
        val d = WorkflowMetadataDescriptor( id = UUID.randomUUID().toString(), name = "WF", version = "2.1" )
        assertEquals( Version(2, 1), MetadataImporter.importWorkflowMetadata( d, namespace ).version )
    }

    @Test
    fun `importWorkflowMetadata defaults version to Version(1) when absent`()
    {
        // version defaults to "" in WorkflowMetadataDescriptor → parseVersion("") → Version(1)
        val d = WorkflowMetadataDescriptor( id = UUID.randomUUID().toString(), name = "WF" )
        assertEquals( Version(1, 0), MetadataImporter.importWorkflowMetadata( d, namespace ).version )
    }

    // ── importWorkflowMetadata: id generation ─────────────────────────────────

    @Test
    fun `importWorkflowMetadata generates deterministic id when id is null`()
    {
        val d = WorkflowMetadataDescriptor( id = null, name = "No ID Workflow" )

        val first = MetadataImporter.importWorkflowMetadata( d, namespace )
        val second = MetadataImporter.importWorkflowMetadata( d, namespace )
        assertEquals( first.id, second.id, "Generated id should be deterministic" )
    }

    @Test
    fun `importWorkflowMetadata generated id is non-null`()
    {
        val d = WorkflowMetadataDescriptor( id = null, name = "WF" )
        assertNotNull( MetadataImporter.importWorkflowMetadata( d, namespace ).id )
    }

    @Test
    fun `importWorkflowMetadata generated id differs across namespaces`()
    {
        val otherNamespace = UUID( "6ba7b811-9dad-11d1-80b4-00c04fd430c8" )
        val d = WorkflowMetadataDescriptor( id = null, name = "WF" )

        assertNotEquals(
            MetadataImporter.importWorkflowMetadata( d, namespace ).id,
            MetadataImporter.importWorkflowMetadata( d, otherNamespace ).id,
        )
    }

    // ── importStepMetadata: explicit step id ──────────────────────────────────

    @Test
    fun `importStepMetadata always uses the provided stepId`()
    {
        val stepId = UUID.randomUUID()
        val d = StepMetadataDescriptor( name = "Step One" )

        val result = MetadataImporter.importStepMetadata( stepId, null, d)
        assertEquals( stepId, result.id )
    }

    @Test
    fun `importStepMetadata preserves name from descriptor`()
    {
        val stepId = UUID.randomUUID()
        val d = StepMetadataDescriptor( name = "Preprocess" )

        assertEquals( "Preprocess", MetadataImporter.importStepMetadata( stepId, null, d ).name )
    }

    @Test
    fun `importStepMetadata preserves stepDescriptorId`()
    {
        val stepId = UUID.randomUUID()
        val descriptorId = "custom-descriptor-id"
        val d = StepMetadataDescriptor( name = "Step" )

        assertEquals( descriptorId, MetadataImporter.importStepMetadata( stepId, descriptorId, d ).descriptorId )
    }

    @Test
    fun `importStepMetadata preserves description from descriptor`()
    {
        val stepId = UUID.randomUUID()
        val d = StepMetadataDescriptor( name = "Step", description = "Does stuff" )

        assertEquals( "Does stuff", MetadataImporter.importStepMetadata( stepId, null, d ).description )
    }

    @Test
    fun `importStepMetadata maps null description to null`()
    {
        val stepId = UUID.randomUUID()
        val d = StepMetadataDescriptor( name = "Step" )

        assertNull( MetadataImporter.importStepMetadata( stepId, null, d ).description )
    }

    @Test
    fun `importStepMetadata parses version from descriptor`()
    {
        val stepId = UUID.randomUUID()
        val d = StepMetadataDescriptor( name = "Step", version = "3.2" )

        assertEquals( Version(3, 2), MetadataImporter.importStepMetadata( stepId, null, d ).version )
    }

    @Test
    fun `importStepMetadata defaults version to Version(1, 0) when absent`()
    {
        val stepId = UUID.randomUUID()
        val d = StepMetadataDescriptor( name = "Step" )

        // descriptor version defaults to null → parseVersion("1.0") → Version(1, 0)
        assertEquals( Version(1, 0), MetadataImporter.importStepMetadata( stepId, null, d ).version )
    }

    // ── importStepMetadata: null descriptor ───────────────────────────────────

    @Test
    fun `importStepMetadata uses stepId toString as name when descriptor is null`()
    {
        val stepId = UUID.randomUUID()

        val result = MetadataImporter.importStepMetadata( stepId, null, null )
        assertEquals( stepId.toString(), result.name )
    }

    @Test
    fun `importStepMetadata produces null description when descriptor is null`()
    {
        val stepId = UUID.randomUUID()

        assertNull( MetadataImporter.importStepMetadata( stepId, null, null).description )
    }

    @Test
    fun `importStepMetadata defaults version to Version(1, 0) when descriptor is null`()
    {
        val stepId = UUID.randomUUID()

        assertEquals( Version(1, 0), MetadataImporter.importStepMetadata( stepId, null, null ).version )
    }

    @Test
    fun `importStepMetadata preserves stepId when descriptor is null`()
    {
        val stepId = UUID.randomUUID()

        assertEquals( stepId, MetadataImporter.importStepMetadata( stepId, null, null ).id )
    }

    // ── tryParseUuid

    @Test
    fun `tryParseUuid returns UUID for valid string`()
    {
        val id = UUID.randomUUID()
        assertEquals( id, tryParseUuid( id.toString() ) )
    }

    @Test
    fun `tryParseUuid returns null for non-UUID string`()
    {
        assertNull( tryParseUuid( "env-conda-001" ) )
        assertNull( tryParseUuid( "step-001" ) )
        assertNull( tryParseUuid( "" ) )
        assertNull( tryParseUuid( "not-a-uuid" ) )
    }

    @Test
    fun `tryParseUuid returns null for malformed UUID string`()
    {
        assertNull( tryParseUuid( "6ba7b810-9dad-11d1-80b4" ) ) // too short
        assertNull( tryParseUuid( "6ba7b810-9dad-11d1-80b4-00c04fd430c8-extra" ) ) // too long
    }
}

