package carp.dsp.core.application.execution.workspace

import dk.cachet.carp.analytics.application.execution.ResourceKind
import dk.cachet.carp.common.application.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Tests for [WorkspaceRefFactory] that verify proper conversion of relative paths to ResourceRef.
 */
class WorkspaceRefFactoryTest {

    // Fixed UUIDs for deterministic testing
    private val fixedStepId1 = UUID.parse("550e8400-e29b-41d4-a716-446655440001")
    private val fixedStepId2 = UUID.parse("550e8400-e29b-41d4-a716-446655440002")

    // --- toWorkspaceRelative ---

    @Test
    fun `toWorkspaceRelative converts path to RELATIVE_PATH ResourceRef`() {
        val relativePath = "steps/$fixedStepId1/outputs/result.json"
        val ref = WorkspaceRefFactory.toWorkspaceRelative(relativePath)
        assertEquals(ResourceKind.RELATIVE_PATH, ref.kind)
        assertEquals(relativePath, ref.value)
    }

    @Test
    fun `toWorkspaceRelative normalizes backslashes to forward slashes before validating`() {
        // Backslash form that, after normalization, is safe
        val ref = WorkspaceRefFactory.toWorkspaceRelative("steps\\${fixedStepId1}\\outputs\\data.csv")
        assertEquals("steps/$fixedStepId1/outputs/data.csv", ref.value)
    }

    @Test
    fun `toWorkspaceRelative rejects empty path`() {
        assertFailsWith<IllegalArgumentException> {
            WorkspaceRefFactory.toWorkspaceRelative("")
        }
    }

    @Test
    fun `toWorkspaceRelative rejects absolute path`() {
        assertFailsWith<IllegalArgumentException> {
            WorkspaceRefFactory.toWorkspaceRelative("/absolute/path.txt")
        }
    }

    @Test
    fun `toWorkspaceRelative rejects path traversal`() {
        assertFailsWith<IllegalArgumentException> {
            WorkspaceRefFactory.toWorkspaceRelative("steps/../../../etc/passwd")
        }
    }

    @Test
    fun `toWorkspaceRelative rejects Windows drive prefix`() {
        assertFailsWith<IllegalArgumentException> {
            WorkspaceRefFactory.toWorkspaceRelative("C:/Windows/System32")
        }
    }

    @Test
    fun `toWorkspaceRelative rejects Windows drive prefix with backslash`() {
        // The backslash is normalized first, so C:\... becomes C:/... and is then caught by ":"
        assertFailsWith<IllegalArgumentException> {
            WorkspaceRefFactory.toWorkspaceRelative("C:\\Windows\\System32")
        }
    }

    // --- stepOutputRef ---

    @Test
    fun `stepOutputRef creates correct reference for simple filename`() {
        val ref = WorkspaceRefFactory.stepOutputRef(fixedStepId1, "metrics.json")
        assertEquals(ResourceKind.RELATIVE_PATH, ref.kind)
        assertEquals("steps/$fixedStepId1/outputs/metrics.json", ref.value)
    }

    @Test
    fun `stepOutputRef creates correct reference for nested path`() {
        val ref = WorkspaceRefFactory.stepOutputRef(fixedStepId1, "results/run1/output.csv")
        assertEquals(ResourceKind.RELATIVE_PATH, ref.kind)
        assertEquals("steps/$fixedStepId1/outputs/results/run1/output.csv", ref.value)
    }

    @Test
    fun `stepOutputRef normalizes backslashes to forward slashes`() {
        val ref = WorkspaceRefFactory.stepOutputRef(fixedStepId1, "subdir\\file.txt")
        assertEquals("steps/$fixedStepId1/outputs/subdir/file.txt", ref.value)
    }

    @Test
    fun `stepOutputRef creates references for different steps independently`() {
        val ref1 = WorkspaceRefFactory.stepOutputRef(fixedStepId1, "output1.json")
        val ref2 = WorkspaceRefFactory.stepOutputRef(fixedStepId2, "output2.json")
        assertEquals("steps/$fixedStepId1/outputs/output1.json", ref1.value)
        assertEquals("steps/$fixedStepId2/outputs/output2.json", ref2.value)
    }

    @Test
    fun `stepOutputRef rejects path traversal in filename`() {
        assertFailsWith<IllegalArgumentException> {
            WorkspaceRefFactory.stepOutputRef(fixedStepId1, "../../../etc/passwd")
        }
    }

    @Test
    fun `stepOutputRef rejects absolute path in filename`() {
        assertFailsWith<IllegalArgumentException> {
            WorkspaceRefFactory.stepOutputRef(fixedStepId1, "/absolute/path.txt")
        }
    }

    @Test
    fun `stepOutputRef rejects Windows drive prefix in filename`() {
        assertFailsWith<IllegalArgumentException> {
            WorkspaceRefFactory.stepOutputRef(fixedStepId1, "C:/output.txt")
        }
    }
}

