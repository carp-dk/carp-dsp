package carp.dsp.core.application.authoring.mapper

import carp.dsp.core.application.authoring.descriptor.PythonTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.ScriptEntryPointDescriptor
import carp.dsp.core.application.authoring.descriptor.StepDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowMetadataDescriptor
import dk.cachet.carp.common.application.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 *
 * Tests UUID v5 computation and deterministic ID generation for workflow components.
 * Ensures reproducibility: same input always produces the same UUID.
 */
class DeterministicUUIDResolverTest
{
    private val testNamespace = UUID.parse( "a1b2c3d4-0000-0000-0000-000000000001" )
    private val resolver = DeterministicUUIDResolverImpl( testNamespace )
    private val dummyTask = PythonTaskDescriptor(
        id = null, name = "task",
        entryPoint = ScriptEntryPointDescriptor("task.py"),
    )

    // ── Step UUID Resolution Tests ────────────────────────────────────────────

    @Test
    fun `resolveStepId with explicit ID parses UUID string`()
    {
        val explicitId = "550e8400-e29b-41d4-a716-446655440000"
        val stepDescriptor = StepDescriptor(
            id = explicitId,
            environmentId = "env-1",
            task = dummyTask
        )

        val result = resolver.resolveStepId( stepDescriptor )

        assertEquals( explicitId, result.toString() )
    }

    @Test
    fun `resolveStepId without ID generates deterministic UUID v5`()
    {
        val stepDescriptor1 = StepDescriptor(
            id = null, // No explicit ID
            environmentId = "env-1",
            task = dummyTask
        )
        val stepDescriptor2 = StepDescriptor(
            id = null, // Same: no explicit ID
            environmentId = "env-1",
            task = dummyTask
        )

        val uuid1 = resolver.resolveStepId( stepDescriptor1 )
        val uuid2 = resolver.resolveStepId( stepDescriptor2 )

        // Determinism: same input (no ID) → same UUID
        assertEquals( uuid1, uuid2, "Should generate same UUID for unnamed steps" )
    }

    @Test
    fun `resolveStepId generates different UUIDs for different explicit IDs`()
    {
        val step1 = StepDescriptor(
            id = "550e8400-e29b-41d4-a716-446655440001",
            environmentId = "env-1",
            task = dummyTask
        )
        val step2 = StepDescriptor(
            id = "550e8400-e29b-41d4-a716-446655440002",
            environmentId = "env-1",
            task = dummyTask
        )

        val uuid1 = resolver.resolveStepId( step1 )
        val uuid2 = resolver.resolveStepId( step2 )

        assertNotEquals( uuid1, uuid2, "Different explicit IDs should produce different UUIDs" )
    }

    @Test
    fun `resolveStepId prefers explicit ID over v5 generation`()
    {
        val explicitId = "ffffffff-ffff-ffff-ffff-ffffffffffff"
        val stepDescriptor = StepDescriptor(
            id = explicitId,
            environmentId = "env-1",
            task = dummyTask
        )

        val result = resolver.resolveStepId( stepDescriptor )

        // Should use explicit ID, not generate one
        assertEquals( explicitId, result.toString() )
    }

    @Test
    fun `resolveStepId is deterministic across resolver instances`()
    {
        val stepDescriptor = StepDescriptor(
            id = null,
            environmentId = "env-1",
            task = dummyTask
        )

        val resolver1 = DeterministicUUIDResolverImpl( testNamespace )
        val resolver2 = DeterministicUUIDResolverImpl( testNamespace )

        val uuid1 = resolver1.resolveStepId( stepDescriptor )
        val uuid2 = resolver2.resolveStepId( stepDescriptor )

        // Same namespace, same input → same UUID across different resolver instances
        assertEquals( uuid1, uuid2, "Determinism across resolver instances" )
    }

    @Test
    fun `resolveStepId different for different namespaces`()
    {
        val namespace1 = UUID.parse( "a1b2c3d4-0000-0000-0000-000000000001" )
        val namespace2 = UUID.parse( "b2c3d4e5-0000-0000-0000-000000000002" )

        val resolver1 = DeterministicUUIDResolverImpl( namespace1 )
        val resolver2 = DeterministicUUIDResolverImpl( namespace2 )

        val stepDescriptor = StepDescriptor(
            id = null,
            environmentId = "env-1",
            task = dummyTask
        )

        val uuid1 = resolver1.resolveStepId( stepDescriptor )
        val uuid2 = resolver2.resolveStepId( stepDescriptor )

        // Different namespaces → different UUIDs
        assertNotEquals( uuid1, uuid2, "Different namespaces produce different UUIDs" )
    }

    // ── Workflow UUID Resolution Tests ────────────────────────────────────────

    @Test
    fun `resolveWorkflowId with explicit ID parses UUID string`()
    {
        val explicitId = "660e8400-e29b-41d4-a716-446655440000"
        val metadata = WorkflowMetadataDescriptor(
            id = explicitId,
            name = "Test Workflow"
        )

        val result = resolver.resolveWorkflowId( metadata )

        assertEquals( explicitId, result.toString() )
    }

    @Test
    fun `resolveWorkflowId without ID generates deterministic UUID v5 from name`()
    {
        val metadata1 = WorkflowMetadataDescriptor(
            id = null,
            name = "Signal Processing Pipeline"
        )
        val metadata2 = WorkflowMetadataDescriptor(
            id = null,
            name = "Signal Processing Pipeline" // Same name
        )

        val uuid1 = resolver.resolveWorkflowId( metadata1 )
        val uuid2 = resolver.resolveWorkflowId( metadata2 )

        // Same name → same UUID
        assertEquals( uuid1, uuid2, "Same workflow name should produce same UUID" )
    }

    @Test
    fun `resolveWorkflowId generates different UUIDs for different names`()
    {
        val metadata1 = WorkflowMetadataDescriptor(
            id = null,
            name = "Signal Processing Pipeline"
        )
        val metadata2 = WorkflowMetadataDescriptor(
            id = null,
            name = "Image Processing Pipeline"
        )

        val uuid1 = resolver.resolveWorkflowId( metadata1 )
        val uuid2 = resolver.resolveWorkflowId( metadata2 )

        assertNotEquals( uuid1, uuid2, "Different names should produce different UUIDs" )
    }

    @Test
    fun `resolveWorkflowId prefers explicit ID over v5 generation`()
    {
        val explicitId = "ffffffff-ffff-ffff-ffff-ffffffffffff"
        val metadata = WorkflowMetadataDescriptor(
            id = explicitId,
            name = "Some Workflow"
        )

        val result = resolver.resolveWorkflowId( metadata )

        // Should use explicit ID, not generate from name
        assertEquals( explicitId, result.toString() )
    }

    @Test
    fun `resolveWorkflowId is deterministic across resolver instances`()
    {
        val metadata = WorkflowMetadataDescriptor(
            id = null,
            name = "ML Pipeline"
        )

        val resolver1 = DeterministicUUIDResolverImpl( testNamespace )
        val resolver2 = DeterministicUUIDResolverImpl( testNamespace )

        val uuid1 = resolver1.resolveWorkflowId( metadata )
        val uuid2 = resolver2.resolveWorkflowId( metadata )

        // Same namespace, same input → same UUID
        assertEquals( uuid1, uuid2, "Determinism across resolver instances" )
    }

    @Test
    fun `resolveWorkflowId different for different namespaces`()
    {
        val namespace1 = UUID.parse( "a1b2c3d4-0000-0000-0000-000000000001" )
        val namespace2 = UUID.parse( "b2c3d4e5-0000-0000-0000-000000000002" )

        val resolver1 = DeterministicUUIDResolverImpl( namespace1 )
        val resolver2 = DeterministicUUIDResolverImpl( namespace2 )

        val metadata = WorkflowMetadataDescriptor(
            id = null,
            name = "Test"
        )

        val uuid1 = resolver1.resolveWorkflowId( metadata )
        val uuid2 = resolver2.resolveWorkflowId( metadata )

        // Different namespaces → different UUIDs
        assertNotEquals( uuid1, uuid2, "Different namespaces produce different UUIDs" )
    }

    // ── Helper Function Tests ─────────────────────────────────────────────────

    @Test
    fun `tryParseUuid parses valid UUID strings`()
    {
        val validId = "550e8400-e29b-41d4-a716-446655440000"

        val result = tryParseUuid( validId )

        assertEquals( validId, result?.toString() )
    }

    @Test
    fun `tryParseUuid returns null for invalid UUID strings`()
    {
        val invalidId = "not-a-uuid"

        val result = tryParseUuid( invalidId )

        assertEquals( null, result )
    }

    @Test
    fun `tryParseUuid returns null for malformed UUID`()
    {
        val malformed = "550e8400-xxxx-41d4-a716-446655440000"

        val result = tryParseUuid( malformed )

        assertEquals( null, result )
    }

    // ── Edge Cases ────────────────────────────────────────────────────────────

    @Test
    fun `resolveWorkflowId handles whitespace in names`()
    {
        val metadata1 = WorkflowMetadataDescriptor(
            id = null,
            name = "Signal Processing"
        )
        val metadata2 = WorkflowMetadataDescriptor(
            id = null,
            name = "Signal Processing " // Trailing space
        )

        val uuid1 = resolver.resolveWorkflowId( metadata1 )
        val uuid2 = resolver.resolveWorkflowId( metadata2 )

        // Should respect whitespace (different names)
        assertNotEquals( uuid1, uuid2 )
    }

    @Test
    fun `resolveStepId handles case-sensitive names`()
    {
        val step1 = StepDescriptor(
            id = "Step-Extract",
            environmentId = "env-1",
            task = dummyTask
        )
        val step2 = StepDescriptor(
            id = "step-extract", // Different case
            environmentId = "env-1",
            task = dummyTask
        )

        val uuid1 = resolver.resolveStepId( step1 )
        val uuid2 = resolver.resolveStepId( step2 )

        // UUIDs are parsed directly, so they must match exactly
        assertNotEquals( uuid1, uuid2 )
    }
}
