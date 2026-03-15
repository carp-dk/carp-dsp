package carp.dsp.core.infrastructure.execution

import dk.cachet.carp.analytics.application.execution.ArtefactMetadata
import dk.cachet.carp.analytics.application.execution.ArtefactStore
import dk.cachet.carp.analytics.application.execution.ResourceKind
import dk.cachet.carp.analytics.application.execution.ResourceRef
import dk.cachet.carp.common.application.UUID
import junit.framework.TestCase.assertTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.test.*

/**
 * Test suite for [FileSystemArtefactStore] implementation.
 * Tests both the ArtefactStore interface contract and filesystem-specific behavior.
 */
class FileSystemArtefactStoreTest {
    private lateinit var tmpDir: Path
    private lateinit var store: ArtefactStore

    @BeforeTest
    fun setup() {
        tmpDir = Files.createTempDirectory("Artefact-store-test")
        store = FileSystemArtefactStore(tmpDir)
    }

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun cleanup() {
        tmpDir.deleteRecursively()
    }

    // ── FileSystem-Specific Tests ──────────────────────────────────────────────

    @Test
    fun `persists and reloads metadata`() {
        val stepId = UUID.randomUUID()
        val outputId = UUID.randomUUID()

        store.recordArtefact(stepId, outputId, ResourceRef(ResourceKind.RELATIVE_PATH, "data"))

        // Create new store instance - should reload from disk
        val newStore = FileSystemArtefactStore(tmpDir)
        val reloaded = newStore.getArtefact(outputId)

        assertNotNull(reloaded)
        assertEquals(outputId, reloaded.outputId)
    }

    @Test
    fun `organizes files by step and output`() {
        val stepId = UUID.randomUUID()
        val outputId = UUID.randomUUID()

        store.recordArtefact(stepId, outputId, ResourceRef(ResourceKind.RELATIVE_PATH, "data"))

        val metadataPath = tmpDir.resolve("outputs").resolve(stepId.toString()).resolve(outputId.toString()).resolve("metadata.json")
        assertTrue(metadataPath.exists())
    }

    @Test
    fun `metadata file contains all artefact information`() {
        val stepId = UUID.randomUUID()
        val outputId = UUID.randomUUID()
        val location = ResourceRef(
            kind = ResourceKind.RELATIVE_PATH,
            value = "steps/$stepId/outputs/data.csv"
        )

        store.recordArtefact(
            stepId = stepId,
            outputId = outputId,
            location = location,
            metadata = ArtefactMetadata(sizeBytes = 2048, sha256 = "def456", contentType = "text/csv")
        )

        // Create new store and verify all metadata persisted
        val newStore = FileSystemArtefactStore(tmpDir)
        val retrieved = newStore.getArtefact(outputId)

        assertNotNull(retrieved)
        assertEquals(outputId, retrieved.outputId)
        assertEquals(location, retrieved.location)
        assertEquals(2048, retrieved.sizeBytes)
        assertEquals("def456", retrieved.sha256)
        assertEquals("text/csv", retrieved.contentType)
    }

    @Test
    fun `handles multiple artefacts across store restarts`() {
        val stepId1 = UUID.randomUUID()
        val stepId2 = UUID.randomUUID()
        val outputId1 = UUID.randomUUID()
        val outputId2 = UUID.randomUUID()

        store.recordArtefact(stepId1, outputId1, ResourceRef(ResourceKind.RELATIVE_PATH, "file1.txt"))
        store.recordArtefact(stepId2, outputId2, ResourceRef(ResourceKind.RELATIVE_PATH, "file2.txt"))

        // Restart store
        val newStore = FileSystemArtefactStore(tmpDir)
        val allArtefacts = newStore.getAllArtefacts()

        assertEquals(2, allArtefacts.size)
        assertTrue(allArtefacts.any { it.outputId == outputId1 })
        assertTrue(allArtefacts.any { it.outputId == outputId2 })
    }

    // ── Recording Artefacts ────────────────────────────────────────────────────

    @Test
    fun `recordArtefact stores artefact successfully`() {
        val stepId = UUID.randomUUID()
        val outputId = UUID.randomUUID()
        val location = ResourceRef(
            kind = ResourceKind.RELATIVE_PATH,
            value = "steps/$stepId/outputs/data.csv"
        )

        val result = store.recordArtefact(
            stepId = stepId,
            outputId = outputId,
            location = location,
            metadata = ArtefactMetadata(sizeBytes = 1024, sha256 = "abc123", contentType = "text/csv")
        )

        assertNotNull(result, "recordArtefact should return a ProducedOutputRef")
        assertEquals(outputId, result.outputId)
        assertEquals(location, result.location)
        assertEquals(1024, result.sizeBytes)
        assertEquals("abc123", result.sha256)
        assertEquals("text/csv", result.contentType)
    }

    @Test
    fun `recordArtefact with minimal fields`() {
        val stepId = UUID.randomUUID()
        val outputId = UUID.randomUUID()
        val location = ResourceRef(
            kind = ResourceKind.RELATIVE_PATH,
            value = "steps/$stepId/outputs/output.txt"
        )

        val result = store.recordArtefact(
            stepId = stepId,
            outputId = outputId,
            location = location
        )

        assertNotNull(result)
        assertEquals(outputId, result.outputId)
        assertEquals(location, result.location)
        assertNull(result.sizeBytes)
        assertNull(result.sha256)
        assertNull(result.contentType)
    }

    @Test
    fun `recordArtefact with URI location`() {
        val stepId = UUID.randomUUID()
        val outputId = UUID.randomUUID()
        val location = ResourceRef(
            kind = ResourceKind.URI,
            value = "file:///tmp/output.json",
            mediaType = "application/json"
        )

        val result = store.recordArtefact(
            stepId = stepId,
            outputId = outputId,
            location = location
        )

        assertNotNull(result)
        assertEquals(outputId, result.outputId)
        assertEquals(location, result.location)
    }

    // ── Retrieving Artefacts ───────────────────────────────────────────────────

    @Test
    fun `getArtefact returns stored artefact`() {
        val stepId = UUID.randomUUID()
        val outputId = UUID.randomUUID()
        val location = ResourceRef(
            kind = ResourceKind.RELATIVE_PATH,
            value = "steps/$stepId/outputs/data.csv"
        )
        store.recordArtefact(stepId, outputId, location, ArtefactMetadata(sizeBytes = 512))

        val retrieved = store.getArtefact(outputId)

        assertNotNull(retrieved)
        assertEquals(outputId, retrieved.outputId)
        assertEquals(location, retrieved.location)
        assertEquals(512, retrieved.sizeBytes)
    }

    @Test
    fun `getArtefact returns null for non-existent outputId`() {
        val nonExistentId = UUID.randomUUID()

        val result = store.getArtefact(nonExistentId)

        assertNull(result, "getArtefact should return null for non-existent outputId")
    }

    @Test
    fun `getArtefactsByStep returns all artefacts for a step`() {
        val stepId = UUID.randomUUID()
        val outputId1 = UUID.randomUUID()
        val outputId2 = UUID.randomUUID()
        val location1 = ResourceRef(
            kind = ResourceKind.RELATIVE_PATH,
            value = "steps/$stepId/outputs/file1.txt"
        )
        val location2 = ResourceRef(
            kind = ResourceKind.RELATIVE_PATH,
            value = "steps/$stepId/outputs/file2.txt"
        )

        store.recordArtefact(stepId, outputId1, location1)
        store.recordArtefact(stepId, outputId2, location2)

        val artefacts = store.getArtefactsByStep(stepId)

        assertEquals(2, artefacts.size, "Should return 2 artefacts for the step")
        assertTrue(artefacts.any { it.outputId == outputId1 })
        assertTrue(artefacts.any { it.outputId == outputId2 })
    }

    @Test
    fun `getArtefactsByStep returns empty list for step with no artefacts`() {
        val stepId = UUID.randomUUID()

        val artefacts = store.getArtefactsByStep(stepId)

        assertTrue(artefacts.isEmpty(), "Should return empty list for step with no artefacts")
    }

    @Test
    fun `getArtefactsByStep does not return artefacts from other steps`() {
        val stepId1 = UUID.randomUUID()
        val stepId2 = UUID.randomUUID()
        val outputId1 = UUID.randomUUID()
        val outputId2 = UUID.randomUUID()
        val location1 = ResourceRef(
            kind = ResourceKind.RELATIVE_PATH,
            value = "steps/$stepId1/outputs/file1.txt"
        )
        val location2 = ResourceRef(
            kind = ResourceKind.RELATIVE_PATH,
            value = "steps/$stepId2/outputs/file2.txt"
        )

        store.recordArtefact(stepId1, outputId1, location1)
        store.recordArtefact(stepId2, outputId2, location2)

        val artefactsStep1 = store.getArtefactsByStep(stepId1)

        assertEquals(1, artefactsStep1.size)
        assertEquals(outputId1, artefactsStep1[0].outputId)
    }

    @Test
    fun `getAllArtefacts returns all stored artefacts`() {
        val stepId1 = UUID.randomUUID()
        val stepId2 = UUID.randomUUID()
        val outputId1 = UUID.randomUUID()
        val outputId2 = UUID.randomUUID()
        val outputId3 = UUID.randomUUID()
        val location1 = ResourceRef(
            kind = ResourceKind.RELATIVE_PATH,
            value = "steps/$stepId1/outputs/file1.txt"
        )
        val location2 = ResourceRef(
            kind = ResourceKind.RELATIVE_PATH,
            value = "steps/$stepId1/outputs/file2.txt"
        )
        val location3 = ResourceRef(
            kind = ResourceKind.RELATIVE_PATH,
            value = "steps/$stepId2/outputs/file3.txt"
        )

        store.recordArtefact(stepId1, outputId1, location1)
        store.recordArtefact(stepId1, outputId2, location2)
        store.recordArtefact(stepId2, outputId3, location3)

        val allArtefacts = store.getAllArtefacts()

        assertEquals(3, allArtefacts.size, "Should return all 3 stored artefacts")
        assertTrue(allArtefacts.any { it.outputId == outputId1 })
        assertTrue(allArtefacts.any { it.outputId == outputId2 })
        assertTrue(allArtefacts.any { it.outputId == outputId3 })
    }

    @Test
    fun `getAllArtefacts returns empty list when no artefacts stored`() {
        val allArtefacts = store.getAllArtefacts()

        assertTrue(allArtefacts.isEmpty(), "Should return empty list when no artefacts stored")
    }

    // ── Path Resolution ────────────────────────────────────────────────────────

    @Test
    fun `resolvePath returns path for existing artefact`() {
        val stepId = UUID.randomUUID()
        val outputId = UUID.randomUUID()
        val location = ResourceRef(
            kind = ResourceKind.RELATIVE_PATH,
            value = "steps/$stepId/outputs/data.csv"
        )
        store.recordArtefact(stepId, outputId, location)

        val path = store.resolvePath(outputId)

        assertNotNull(path, "resolvePath should return a path for existing artefact")
        assertTrue(path.isNotEmpty(), "Path should not be empty")
    }

    @Test
    fun `resolvePath returns null for non-existent outputId`() {
        val nonExistentId = UUID.randomUUID()

        val path = store.resolvePath(nonExistentId)

        assertNull(path, "resolvePath should return null for non-existent outputId")
    }

    @Test
    fun `resolvePath resolves relative paths correctly`() {
        val stepId = UUID.randomUUID()
        val outputId = UUID.randomUUID()
        val relativePath = "steps/$stepId/outputs/data.csv"
        val location = ResourceRef(
            kind = ResourceKind.RELATIVE_PATH,
            value = relativePath
        )
        store.recordArtefact(stepId, outputId, location)

        val resolved = store.resolvePath(outputId)

        assertNotNull(resolved)
        // Normalize path separators for cross-platform comparison
        val normalizedResolved = resolved.replace('\\', '/')
        val normalizedRelative = relativePath.replace('\\', '/')
        assertTrue(
            normalizedResolved.contains(normalizedRelative),
            "Resolved path should contain the relative path. Expected: $normalizedRelative, Got: $normalizedResolved"
        )
    }

    // ── Edge Cases and Multiple Operations ────────────────────────────────────

    @Test
    fun `can record multiple artefacts for same step`() {
        val stepId = UUID.randomUUID()
        val outputIds = (1..5).map { UUID.randomUUID() }

        outputIds.forEach { outputId ->
            val location = ResourceRef(
                kind = ResourceKind.RELATIVE_PATH,
                value = "steps/$stepId/outputs/file_$outputId.txt"
            )
            store.recordArtefact(stepId, outputId, location)
        }

        val artefacts = store.getArtefactsByStep(stepId)
        assertEquals(5, artefacts.size)
        outputIds.forEach { outputId ->
            assertTrue(artefacts.any { it.outputId == outputId })
        }
    }

    @Test
    fun `recording artefact with duplicate outputId replaces existing`() {
        val stepId = UUID.randomUUID()
        val outputId = UUID.randomUUID()
        val location1 = ResourceRef(
            kind = ResourceKind.RELATIVE_PATH,
            value = "steps/$stepId/outputs/old.txt"
        )
        val location2 = ResourceRef(
            kind = ResourceKind.RELATIVE_PATH,
            value = "steps/$stepId/outputs/new.txt"
        )

        store.recordArtefact(stepId, outputId, location1)
        store.recordArtefact(stepId, outputId, location2)

        val retrieved = store.getArtefact(outputId)
        assertNotNull(retrieved)
        assertEquals(location2, retrieved.location, "Should have updated location")
    }

    @Test
    fun `store handles large number of artefacts`() {
        val stepIds = (1..10).map { UUID.randomUUID() }
        val totalArtefacts = 50

        var artefactCount = 0
        stepIds.forEach { stepId ->
            repeat(5) {
                val outputId = UUID.randomUUID()
                val location = ResourceRef(
                    kind = ResourceKind.RELATIVE_PATH,
                    value = "steps/$stepId/outputs/file_$artefactCount.txt"
                )
                store.recordArtefact(stepId, outputId, location)
                artefactCount++
            }
        }

        val allArtefacts = store.getAllArtefacts()
        assertEquals(totalArtefacts, allArtefacts.size)
    }

    @Test
    fun `store handles artefacts with special characters in paths`() {
        val stepId = UUID.randomUUID()
        val outputId = UUID.randomUUID()
        val location = ResourceRef(
            kind = ResourceKind.RELATIVE_PATH,
            value = "steps/$stepId/outputs/data with spaces & special#chars.csv"
        )

        val result = store.recordArtefact(stepId, outputId, location)

        assertNotNull(result)
        assertEquals(location.value, result.location.value)

        val retrieved = store.getArtefact(outputId)
        assertNotNull(retrieved)
        assertEquals(location.value, retrieved.location.value)
    }

    @Test
    fun `concurrent step artefacts are isolated`() {
        val stepIds = (1..3).map { UUID.randomUUID() }

        // Record artefacts for each step
        stepIds.forEach { stepId ->
            repeat(3) { i ->
                val outputId = UUID.randomUUID()
                val location = ResourceRef(
                    kind = ResourceKind.RELATIVE_PATH,
                    value = "steps/$stepId/outputs/file$i.txt"
                )
                store.recordArtefact(stepId, outputId, location)
            }
        }

        // Verify each step has exactly 3 artefacts
        stepIds.forEach { stepId ->
            val artefacts = store.getArtefactsByStep(stepId)
            assertEquals(3, artefacts.size, "Each step should have 3 artefacts")
        }

        // Verify total
        assertEquals(9, store.getAllArtefacts().size)
    }
}
