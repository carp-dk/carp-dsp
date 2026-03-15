package carp.dsp.core.infrastructure.execution

import dk.cachet.carp.analytics.application.execution.ArtefactStore
import dk.cachet.carp.analytics.application.execution.ProducedOutputRef
import dk.cachet.carp.analytics.application.execution.ResourceRef
import dk.cachet.carp.analytics.application.plan.*
import dk.cachet.carp.common.application.UUID
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ArtefactRecorderTest {

    private lateinit var tmpDir: java.nio.file.Path
    private lateinit var recorder: ArtefactRecorder
    private lateinit var store: MockArtefactStore

    @BeforeTest
    fun setup() {
        tmpDir = Files.createTempDirectory("artefact-recorder-test")
        store = MockArtefactStore()
        recorder = FileSystemArtefactRecorder()
    }

    @Test
    fun recordsExistingArtefact() {
        val stepId = UUID.randomUUID()
        val outputId = UUID.randomUUID()

        // Create output file
        val outputDir = tmpDir.resolve("outputs").resolve(outputId.toString())
        outputDir.createDirectories()
        outputDir.resolve("data").writeText("test data")

        // Create step
        val step = PlannedStep(
            stepId = stepId,
            name = "test",
            process = CommandSpec("echo", listOf(ExpandedArg.Literal("Hello"))),
            bindings = ResolvedBindings(
                emptyMap(),
                mapOf(outputId to DataRef(outputId, "test"))
            ),
            environmentRef = UUID.randomUUID()
        )

        // Record
        val artefacts = recorder.recordArtefacts(step, tmpDir, store)

        // Verify
        assertEquals(1, artefacts.size)
        assertNotNull(artefacts[0])
    }

    @Test
    fun skipsNonExistentArtefacts() {
        val stepId = UUID.randomUUID()
        val outputId = UUID.randomUUID()

        val step = PlannedStep(
            stepId = stepId,
            name = "test",
            process = CommandSpec("echo", listOf(ExpandedArg.Literal("Hello"))),
            bindings = ResolvedBindings(
                emptyMap(),
                mapOf(outputId to DataRef(outputId, "test"))
            ),
            environmentRef = UUID.randomUUID()
        )

        val artefacts = recorder.recordArtefacts(step, tmpDir, store)

        assertEquals(0, artefacts.size) // File didn't exist
    }

    // Mock Artefact store for testing
    private class MockArtefactStore : ArtefactStore {
        val recorded = mutableListOf<ProducedOutputRef>()

        override fun recordArtefact(
            stepId: UUID,
            outputId: UUID,
            location: ResourceRef,
            metadata: dk.cachet.carp.analytics.application.execution.ArtefactMetadata
        ): ProducedOutputRef {
            val ref = ProducedOutputRef(outputId, location, metadata.sizeBytes, metadata.sha256, metadata.contentType)
            recorded.add(ref)
            return ref
        }

        override fun getArtefact(outputId: UUID): ProducedOutputRef? = recorded.firstOrNull { it.outputId == outputId }
        override fun getArtefactsByStep(stepId: UUID): List<ProducedOutputRef> = recorded
        override fun getAllArtefacts(): List<ProducedOutputRef> = recorded
        override fun resolvePath(outputId: UUID): String? = null
    }
}
