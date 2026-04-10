package carp.dsp.core.infrastructure.execution

import carp.dsp.core.application.plan.createBindingsWithInputsOutputs
import carp.dsp.core.application.plan.createResolvedOutput
import dk.cachet.carp.analytics.application.execution.ArtefactStore
import dk.cachet.carp.analytics.application.execution.ProducedOutputRef
import dk.cachet.carp.analytics.application.execution.ResourceRef
import dk.cachet.carp.analytics.application.plan.CommandSpec
import dk.cachet.carp.analytics.application.plan.ExpandedArg
import dk.cachet.carp.analytics.application.plan.PlannedStep
import dk.cachet.carp.analytics.application.plan.ResolvedBindings
import dk.cachet.carp.analytics.domain.data.FileFormat
import dk.cachet.carp.analytics.domain.workflow.StepMetadata
import dk.cachet.carp.common.application.UUID
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

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

        val stepWorkingDir = tmpDir

        val outputRelativePath = "outputs/$outputId/data"
        val outputFile = stepWorkingDir.resolve(outputRelativePath)
        outputFile.parent?.createDirectories()
        outputFile.writeText("test data")

        // Create step with RELATIVE path
        val step = PlannedStep(
            metadata = StepMetadata(id = stepId, name = "test"),
            process = CommandSpec("echo", listOf(ExpandedArg.Literal("Hello"))),
            bindings = ResolvedBindings(
                outputs = mapOf(
                    outputId to createResolvedOutput(
                        id = outputId,
                        format = FileFormat.CSV,
                        path = outputRelativePath,
                    )
                )
            ),
                environmentRef = UUID.randomUUID()
        )

        // Record with the same working directory
        val artefacts = recorder.recordArtefacts(step, stepWorkingDir, store)

        assertEquals(1, artefacts.size)
    }

    @Test
    fun skipsNonExistentArtefacts() {
        val stepId = UUID.randomUUID()
        val outputId = UUID.randomUUID()

        val step = PlannedStep(
            metadata = StepMetadata(
                id = stepId,
                name = "test"
            ),
            process = CommandSpec("echo", listOf(ExpandedArg.Literal("Hello"))),
            bindings = createBindingsWithInputsOutputs(outputId),
            environmentRef = UUID.randomUUID()
        )

        val artefacts = recorder.recordArtefacts(step, tmpDir, store)

        assertEquals(0, artefacts.size)
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
