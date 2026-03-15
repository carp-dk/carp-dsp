package carp.dsp.core.integration

import carp.dsp.core.infrastructure.execution.CommandStepRunner
import carp.dsp.core.infrastructure.execution.FileSystemArtefactRecorder
import carp.dsp.core.infrastructure.execution.FileSystemArtefactStore
import carp.dsp.core.infrastructure.execution.FileSystemStepLogRecorder
import carp.dsp.core.infrastructure.runtime.JvmCommandRunner
import dk.cachet.carp.analytics.application.execution.ExecutionStatus
import dk.cachet.carp.analytics.application.execution.ResourceKind
import dk.cachet.carp.analytics.application.execution.workspace.ExecutionWorkspace
import dk.cachet.carp.analytics.application.execution.workspace.WorkspaceManager
import dk.cachet.carp.analytics.application.plan.CommandSpec
import dk.cachet.carp.analytics.application.plan.DataRef
import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import dk.cachet.carp.analytics.application.plan.ExpandedArg
import dk.cachet.carp.analytics.application.plan.PlannedStep
import dk.cachet.carp.analytics.application.plan.ResolvedBindings
import dk.cachet.carp.common.application.UUID
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration test for complete step execution pipeline.
 */
class StepExecutionIntegrationTest {

    private lateinit var tmpDir: Path
    private lateinit var artefactStore: FileSystemArtefactStore
    private lateinit var runner: CommandStepRunner
    private lateinit var workspace: ExecutionWorkspace
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    @BeforeTest
    fun setup() {
        tmpDir = Files.createTempDirectory("step-execution-integration-test")

        artefactStore = FileSystemArtefactStore(tmpDir.resolve("artefacts"))
        runner = CommandStepRunner(
            workspaceManager = RealWorkspaceManager(tmpDir),
            commandRunner = JvmCommandRunner(),
            artefactStore = artefactStore,
            options = CommandStepRunner.Options(
                artefactRecorder = FileSystemArtefactRecorder(),
                logRecorder = FileSystemStepLogRecorder()
            )
        )

        workspace = ExecutionWorkspace(
            runId = UUID.randomUUID(),
            executionRoot = tmpDir.toString()
        )
    }

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun cleanup() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun executesStepAndRecordsArtefacts() {
        val step = createStepThatCreatesOutput()

        val result = runner.run(step, workspace)

        assertEquals(ExecutionStatus.SUCCEEDED, result.status)
        assertTrue(result.outputs?.isNotEmpty() ?: false)

        val artefact = result.outputs?.first()
        assertNotNull(artefact)
        assertTrue(artefact.location.value.contains("steps/${step.stepId}/outputs/"))
        assertNotNull(artefact.sizeBytes)
        assertNotNull(artefact.sha256)

        // Command must create the output data file in the step working directory.
        val outputId = step.bindings.outputs.keys.first()
        val dataPath = tmpDir
            .resolve("steps")
            .resolve(step.stepId.toString())
            .resolve("work")
            .resolve("outputs")
            .resolve(outputId.toString())
            .resolve("data")
        assertTrue(dataPath.exists())
    }

    @Test
    fun executesStepAndRecordsLogs() {
        val step = createStepWithOutput()

        val result = runner.run(step, workspace)

        assertNotNull(result.detail?.stdout)
        assertEquals(ResourceKind.RELATIVE_PATH, result.detail?.stdout?.kind)
        assertTrue(result.detail?.stdout?.value?.contains("logs/") ?: false)
        assertFalse(result.detail?.stdout?.value?.contains("data:") ?: false)

        val logPath = tmpDir.resolve(result.detail?.stdout?.value ?: "")
        assertTrue(logPath.toFile().exists())
        val logContent = logPath.readText()
        assertTrue(logContent.contains("=== STDOUT ==="))
        assertTrue(logContent.contains("simulated stdout"))
    }

    @Test
    fun recordsArtefactsWithCorrectMetadata() {
        val step = createStepThatCreatesOutput()

        val result = runner.run(step, workspace)

        val artefact = result.outputs?.first()
        assertNotNull(artefact)

        assertNotNull(artefact.sha256)
        assertEquals(64, artefact.sha256?.length)

        assertNotNull(artefact.sizeBytes)
        assertTrue(artefact.sizeBytes!! > 0)

        assertNotNull(artefact.contentType)
        assertEquals("text/plain", artefact.contentType)
    }

    @Test
    fun handlesMultipleOutputs() {
        val step = createStepThatCreatesMultipleOutputs()

        val result = runner.run(step, workspace)

        assertEquals(2, result.outputs?.size)
        result.outputs?.forEach { artefact ->
            assertNotNull(artefact.sha256)
            assertNotNull(artefact.sizeBytes)
            assertNotNull(artefact.contentType)
        }
    }

    @Test
    fun capturesStdoutAndStderr() {
        val step = createStepWithBothOutputStreams()

        val result = runner.run(step, workspace)

        assertNotNull(result.detail?.stdout)
        val logPath = tmpDir.resolve(result.detail?.stdout?.value ?: "")
        val logContent = logPath.readText()

        assertTrue(logContent.contains("=== STDOUT ==="))
        assertTrue(logContent.contains("=== STDERR ==="))
        assertTrue(logContent.contains("stdout message"))
        assertTrue(logContent.contains("stderr message"))

        // Paths in logs should be human-readable relative paths, not opaque IDs-only entries.
        assertFalse(logContent.contains("data:text/plain,"))
    }

    @Test
    fun failedStepSkipsArtifactRecording() {
        val step = createFailingStep()

        val result = runner.run(step, workspace)

        assertEquals(ExecutionStatus.FAILED, result.status)
        assertNotNull(result.failure)
        assertTrue(result.outputs?.isEmpty() ?: true)
        assertNotNull(result.detail?.stdout)
    }

    @Test
    fun logsAreRecordedEvenWhenArtefactsEmpty() {
        val step = createStepWithNoOutputs()

        val result = runner.run(step, workspace)

        assertEquals(ExecutionStatus.SUCCEEDED, result.status)
        assertTrue(result.outputs?.isEmpty() ?: true)
        assertNotNull(result.detail?.stdout)
    }

    @Test
    fun workspaceLayoutIsCorrect() {
        val step = createStepThatCreatesOutput()

        runner.run(step, workspace)

        assertTrue(tmpDir.resolve("steps").toFile().isDirectory)
        assertTrue(tmpDir.resolve("steps/${step.stepId}").toFile().isDirectory)
        assertTrue(tmpDir.resolve("logs").toFile().isDirectory)
        assertTrue(tmpDir.resolve("artefacts").toFile().isDirectory)
    }

    @Test
    fun artefactStoreIndexingWorks() {
        val step = createStepThatCreatesOutput()

        val result = runner.run(step, workspace)

        val outputId = step.bindings.outputs.keys.first()
        val storedArtifact = artefactStore.getArtefact(outputId)
        assertNotNull(storedArtifact)
        assertEquals(result.outputs?.first()?.sha256, storedArtifact.sha256)
        assertEquals(outputId, storedArtifact.outputId)
        assertEquals(ResourceKind.RELATIVE_PATH, storedArtifact.location.kind)
        assertTrue(storedArtifact.location.value.contains(step.stepId.toString()))
        assertTrue(storedArtifact.location.value.contains(outputId.toString()))

        val dataPath = tmpDir
            .resolve("steps")
            .resolve(step.stepId.toString())
            .resolve("work")
            .resolve("outputs")
            .resolve(outputId.toString())
            .resolve("data")
        assertTrue(dataPath.exists())
    }

    private fun createStepThatCreatesOutput(): PlannedStep {
        val stepId = UUID.randomUUID()
        val outputId = UUID.randomUUID()

        return plannedStep(
            stepId = stepId,
            command = createOutputCommand(outputId),
            outputs = mapOf(outputId to DataRef(outputId, "text/plain"))
        )
    }

    private fun createStepWithOutput(): PlannedStep = plannedStep(command = shellCommand("echo simulated stdout"))

    private fun createStepThatCreatesMultipleOutputs(): PlannedStep {
        val stepId = UUID.randomUUID()
        val outputId1 = UUID.randomUUID()
        val outputId2 = UUID.randomUUID()

        return plannedStep(
            stepId = stepId,
            command = createMultipleOutputsCommand(outputId1, outputId2),
            outputs = mapOf(
                outputId1 to DataRef(outputId1, "text/plain"),
                outputId2 to DataRef(outputId2, "text/csv")
            )
        )
    }

    private fun createStepWithBothOutputStreams(): PlannedStep = plannedStep(
        command = shellCommand("echo stdout message && stderr message")
    )

    private fun createFailingStep(): PlannedStep = plannedStep(command = shellCommand("echo simulated failure && fail"))

    private fun createStepWithNoOutputs(): PlannedStep = plannedStep(command = shellCommand("echo simulated stdout"))

    private fun plannedStep(
        stepId: UUID = UUID.randomUUID(),
        command: CommandSpec,
        outputs: Map<UUID, DataRef> = emptyMap()
    ): PlannedStep = PlannedStep(
        stepId = stepId,
        name = "integration-step",
        process = command,
        bindings = ResolvedBindings(outputs = outputs),
        environmentRef = null
    )

    private fun createOutputCommand(outputId: UUID): CommandSpec {
        val script = if (isWindows) {
            "mkdir \"outputs\\$outputId\" && (echo test output> \"outputs\\$outputId\\data\") && echo simulated stdout"
        } else {
            "mkdir -p \"outputs/$outputId\" && printf '%s' 'test output' > \"outputs/$outputId/data\" && echo simulated stdout"
        }
        return shellCommand(script)
    }

    private fun createMultipleOutputsCommand(outputId1: UUID, outputId2: UUID): CommandSpec {
        val script = if (isWindows) {
            "mkdir \"outputs\\$outputId1\" && (echo first> \"outputs\\$outputId1\\data\") && mkdir \"outputs\\$outputId2\" && (echo second> \"outputs\\$outputId2\\data\") && echo simulated stdout"
        } else {
            "mkdir -p \"outputs/$outputId1\" \"outputs/$outputId2\" && printf '%s' 'first' > \"outputs/$outputId1/data\" && printf '%s' 'second' > \"outputs/$outputId2/data\" && echo simulated stdout"
        }
        return shellCommand(script)
    }

    private fun shellCommand(scenario: String): CommandSpec {
        val script = when {
            scenario.endsWith("&& stderr message") && isWindows ->
                "echo stdout message && (1>&2 echo stderr message)"
            scenario.endsWith("&& stderr message") ->
                "echo stdout message && echo stderr message 1>&2"
            scenario.endsWith("&& fail") && isWindows ->
                "(1>&2 echo simulated failure) && exit /b 1"
            scenario.endsWith("&& fail") ->
                "echo simulated failure 1>&2; exit 1"
            else -> scenario
        }

        return if (isWindows) {
            CommandSpec(
                executable = "cmd",
                args = listOf(
                    ExpandedArg.Literal("/c"),
                    ExpandedArg.Literal(script)
                )
            )
        } else {
            CommandSpec(
                executable = "sh",
                args = listOf(
                    ExpandedArg.Literal("-c"),
                    ExpandedArg.Literal(script)
                )
            )
        }
    }

    private class RealWorkspaceManager(private val root: Path) : WorkspaceManager {
        override fun create(plan: ExecutionPlan, runId: UUID): ExecutionWorkspace =
            ExecutionWorkspace(runId = runId, executionRoot = root.toString())

        override fun prepareStepDirectories(workspace: ExecutionWorkspace, stepId: UUID) {
            root.resolve("steps").resolve(stepId.toString()).resolve("work").createDirectories()
            root.resolve("steps").resolve(stepId.toString()).resolve("inputs").createDirectories()
            root.resolve("steps").resolve(stepId.toString()).resolve("outputs").createDirectories()
            root.resolve("steps").resolve(stepId.toString()).resolve("logs").createDirectories()
            root.resolve("logs").createDirectories()
        }

        override fun resolveStepWorkingDir(workspace: ExecutionWorkspace, stepId: UUID): String {
            val dir = root.resolve("steps").resolve(stepId.toString()).resolve("work")
            dir.createDirectories()
            return dir.toString()
        }
    }
}
