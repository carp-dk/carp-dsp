package carp.dsp.core.infrastructure.execution

import carp.dsp.core.application.plan.createResolvedOutput
import dk.cachet.carp.analytics.application.execution.ArtefactMetadata
import dk.cachet.carp.analytics.application.execution.ArtefactStore
import dk.cachet.carp.analytics.application.execution.ExecutionStatus
import dk.cachet.carp.analytics.application.execution.FailureKind
import dk.cachet.carp.analytics.application.execution.ProducedOutputRef
import dk.cachet.carp.analytics.application.execution.ResourceKind
import dk.cachet.carp.analytics.application.execution.ResourceRef
import dk.cachet.carp.analytics.application.execution.RunPolicy
import dk.cachet.carp.analytics.application.execution.StepLogRecorder
import dk.cachet.carp.analytics.application.execution.workspace.ExecutionWorkspace
import dk.cachet.carp.analytics.application.execution.workspace.StepInfo
import dk.cachet.carp.analytics.application.execution.workspace.WorkspaceManager
import dk.cachet.carp.analytics.application.plan.CommandSpec
import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import dk.cachet.carp.analytics.application.plan.ExpandedArg
import dk.cachet.carp.analytics.application.plan.InTasksRun
import dk.cachet.carp.analytics.application.plan.PlannedStep
import dk.cachet.carp.analytics.application.plan.ResolvedBindings
import dk.cachet.carp.analytics.application.runtime.CommandResult
import dk.cachet.carp.analytics.application.runtime.CommandRunner
import dk.cachet.carp.analytics.domain.data.FileFormat
import dk.cachet.carp.analytics.domain.workflow.StepMetadata
import dk.cachet.carp.common.application.UUID
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandStepRunnerTest {

    private lateinit var testWorkspaceRoot: Path

    @OptIn(ExperimentalPathApi::class)
    @BeforeTest
    fun setup() {
        testWorkspaceRoot = Files.createTempDirectory("command-step-runner-test")
    }

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun cleanup() {
        if (::testWorkspaceRoot.isInitialized) {
            testWorkspaceRoot.deleteRecursively()
        }
    }

    /**
     * Mock ArtefactStore for testing artefact recording.
     */
    private class MockArtefactStore : ArtefactStore {
        val recorded = mutableListOf<RecordedCall>()

        data class RecordedCall(
            val stepId: UUID,
            val outputId: UUID,
            val location: ResourceRef,
            val metadata: ArtefactMetadata
        )

        override fun recordArtefact(
            stepId: UUID,
            outputId: UUID,
            location: ResourceRef,
            metadata: ArtefactMetadata
        ): ProducedOutputRef {
            recorded.add(RecordedCall(stepId, outputId, location, metadata))
            return ProducedOutputRef(
                outputId = outputId,
                location = location,
                sizeBytes = metadata.sizeBytes,
                sha256 = metadata.sha256,
                contentType = metadata.contentType
            )
        }

        override fun getArtefact(outputId: UUID): ProducedOutputRef? = null
        override fun getArtefactsByStep(stepId: UUID): List<ProducedOutputRef> = emptyList()
        override fun getAllArtefacts(): List<ProducedOutputRef> = emptyList()
        override fun resolvePath(outputId: UUID): String? = null
    }

    private class CapturingWorkspaceManager(
        private val executionRoot: String
    ) : WorkspaceManager {
        override fun create( plan: ExecutionPlan, runId: UUID ) =
            ExecutionWorkspace( runId = runId, executionRoot = executionRoot, workflowName = "workflow" )
        override fun prepareStepDirectories( workspace: ExecutionWorkspace, stepId: UUID ) = Unit
    }

    /**
     * CommandRunner that records the last invocation so tests can assert on what was passed.
     * Falls back to [delegate] for the actual result.
     */
    private class RecordingCommandRunner(
        private val delegate: CommandRunner
    ) : CommandRunner {
        var lastCommand: CommandSpec? = null
        var lastPolicy: RunPolicy? = null

        override fun run(command: CommandSpec, policy: RunPolicy): CommandResult {
            lastCommand = command
            lastPolicy = policy
            return delegate.run(command, policy)
        }
    }

    private class FixedCommandRunner(
        private val exitCode: Int = 0,
        private val stdout: String = "",
        private val stderr: String = "",
        private val timedOut: Boolean = false
    ) : CommandRunner {
        override fun run(command: CommandSpec, policy: RunPolicy) = CommandResult(
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr,
            durationMs = 10L,
            timedOut = timedOut
        )
    }

    // Helpers

    private fun createExecutionWorkspace(steps: List<PlannedStep> = emptyList()): ExecutionWorkspace {
        val stepInfos = steps.mapIndexed { index, step ->
            step.metadata.id to StepInfo(
                id = step.metadata.id,
                name = step.metadata.name,
                executionIndex = index
            )
        }.toMap()
        return ExecutionWorkspace(
            runId = UUID.randomUUID(),
            executionRoot = testWorkspaceRoot.toString(),
            workflowName = "test-workflow",
            stepInfos = stepInfos
        )
    }

    private fun commandStep(
        name: String = "step",
        executable: String = "echo",
        args: List<String> = listOf(name)
    ) = PlannedStep(
        metadata = StepMetadata(
            id = UUID.randomUUID(),
            name = name
        ),
        process = CommandSpec(executable, args.map { ExpandedArg.Literal(it) }),
        bindings = ResolvedBindings(),
        environmentRef = UUID.randomUUID()
    )

    private fun inProcessStep(name: String = "in-process") = PlannedStep(
        metadata = StepMetadata(
            id = UUID.randomUUID(),
            name = name
        ),
        process = InTasksRun(operationId = "some.operation"),
        bindings = ResolvedBindings(),
        environmentRef = UUID.randomUUID()
    )

    /** Convenience: creates a [CommandStepRunner] backed by a [FixedCommandRunner]. */
    private fun runner(
        exitCode: Int = 0,
        stdout: String = "",
        stderr: String = "",
        timedOut: Boolean = false,
        artefactStore: ArtefactStore = MockArtefactStore()
    ) = CommandStepRunner(
        workspaceManager = CapturingWorkspaceManager(testWorkspaceRoot.toString()),
        commandRunner = FixedCommandRunner(exitCode, stdout, stderr, timedOut),
        artefactStore = artefactStore
    )

    // Artefact Recording

    @Test
    fun `artifacts are recorded for successful step with output files`() {
        val stepId = UUID.randomUUID()
        val outputId = UUID.randomUUID()
        val stepDir = "01_test_step"
        val outputsDir = testWorkspaceRoot.resolve("steps/$stepDir/outputs")
        Files.createDirectories(outputsDir)

        // Create output file
        val dataFile = outputsDir.resolve("result.csv")
        dataFile.writeText("test data content")

        val mockStore = MockArtefactStore()
        val step = PlannedStep(
            metadata = StepMetadata(
                id = stepId,
                name = "test-step"
            ),
            process = CommandSpec("echo", listOf(ExpandedArg.Literal("test"))),
            bindings = ResolvedBindings(
                outputs = mapOf(
                    outputId to createResolvedOutput(
                        id = outputId,
                        format = FileFormat.CSV,
                        resolvedPath = "steps/$stepDir/outputs/result.csv"
                    )
                )
            ),
            environmentRef = UUID.randomUUID()
        )

        val result = runner(
            exitCode = 0,
            artefactStore = mockStore
        ).run(step, createExecutionWorkspace(listOf(step)))

        assertEquals(ExecutionStatus.SUCCEEDED, result.status)
        assertEquals(1, mockStore.recorded.size)

        val recorded = mockStore.recorded[0]
        assertEquals(stepId, recorded.stepId)
        assertEquals(outputId, recorded.outputId)
        assertEquals(ResourceKind.RELATIVE_PATH, recorded.location.kind)
        assertTrue(recorded.location.value.contains("steps/$stepDir/outputs/result.csv"))
        assertNotNull(recorded.metadata.sizeBytes)
        assertTrue(recorded.metadata.sizeBytes!! > 0)
        assertNotNull(recorded.metadata.sha256)
        assertNotNull(recorded.metadata.contentType)
    }

    @Test
    fun `no artifacts recorded when step fails`() {
        UUID.randomUUID()
        val outputId = UUID.randomUUID()
        val stepDir = "01_test_step"
        val outputsDir = testWorkspaceRoot.resolve("steps/$stepDir/outputs")
        Files.createDirectories(outputsDir)

        // Create output file (even though step fails)
        outputsDir.resolve("result.csv").writeText("test data")

        val mockStore = MockArtefactStore()
        val step = PlannedStep(
            metadata = StepMetadata(
                id = UUID.randomUUID(),
                name = "test-step"
            ),
            process = CommandSpec("echo", listOf(ExpandedArg.Literal("test"))),
            bindings = ResolvedBindings(
                outputs = mapOf(outputId to createResolvedOutput(outputId, "text/plain", path = "steps/$stepDir/outputs/result.csv"))
            ),
            environmentRef = UUID.randomUUID()
        )

        val result = runner(
            exitCode = 1, // Failed
            artefactStore = mockStore
        ).run(step, createExecutionWorkspace(listOf(step)))

        assertEquals(ExecutionStatus.FAILED, result.status)
        assertEquals(0, mockStore.recorded.size, "No artifacts should be recorded for failed steps")
    }

    @Test
    fun `artifacts recorded with correct SHA-256 hash`() {
        val outputId = UUID.randomUUID()
        val stepDir = "01_test_step"
        val outputsDir = testWorkspaceRoot.resolve("steps/$stepDir/outputs")
        Files.createDirectories(outputsDir)

        val dataFile = outputsDir.resolve("data")
        val testContent = "Hello, World!"
        dataFile.writeText(testContent)

        // Expected SHA-256 for "Hello, World!"
        val expectedSha256 = "dffd6021bb2bd5b0af676290809ec3a53191dd81c7f70a4b28688a362182986f"

        val mockStore = MockArtefactStore()
        val step = PlannedStep(
            metadata = StepMetadata(
                id = UUID.randomUUID(),
                name = "test-step"
            ),
            process = CommandSpec("echo", listOf(ExpandedArg.Literal("test"))),
            bindings = ResolvedBindings(
                outputs = mapOf(
                    outputId to createResolvedOutput(
                        id = outputId,
                        format = FileFormat.CSV,
                        resolvedPath = "steps/$stepDir/outputs/data"
                    )
                )
            ),
            environmentRef = UUID.randomUUID()
        )

        runner(
            exitCode = 0,
            artefactStore = mockStore
        ).run(step, createExecutionWorkspace(listOf(step)))

        assertEquals(1, mockStore.recorded.size)
        assertEquals(expectedSha256, mockStore.recorded[0].metadata.sha256)
    }

    @Test
    fun `content type determined from file extension`() {
        val testCases = mapOf(
            "data.json" to "application/json",
            "data.csv" to "text/csv",
            "data.xml" to "application/xml",
            "data.txt" to "text/plain",
            "data.pdf" to "application/pdf",
            "data.png" to "image/png"
        )

        testCases.forEach { (filename, expectedContentType) ->
            val outputId = UUID.randomUUID()
            val stepDir = "01_test_step"
            val outputsDir = testWorkspaceRoot.resolve("steps/$stepDir/outputs")
            Files.createDirectories(outputsDir)

            val dataFile = outputsDir.resolve(filename)
            dataFile.writeText("test")

            val mockStore = MockArtefactStore()
            val step = PlannedStep(
                metadata = StepMetadata(
                    id = UUID.randomUUID(),
                    name = "test-step"
                ),
                process = CommandSpec("echo", listOf(ExpandedArg.Literal("test"))),
                bindings = ResolvedBindings(
                    outputs = mapOf(
                        outputId to createResolvedOutput(
                            id = outputId,
                            resolvedPath = "steps/$stepDir/outputs/$filename"
                        )
                    )
                ),
                environmentRef = UUID.randomUUID()
            )

            val recorder = FileSystemArtefactRecorder()
            val artefacts = recorder.recordArtefacts(step, testWorkspaceRoot, mockStore)

            assertEquals(1, artefacts.size, "Expected 1 artefact for $filename")

            // Verify the content type was correctly detected
            val recordedCall = mockStore.recorded[0].metadata.contentType
            assertEquals(
                expectedContentType, recordedCall,
                "Content type mismatch for $filename"
            )
        }
    }

    @Test
    fun `multiple output artifacts are recorded`() {
        val stepId = UUID.randomUUID()
        val outputId1 = UUID.randomUUID()
        val outputId2 = UUID.randomUUID()
        val outputId3 = UUID.randomUUID()
        val stepDir = "01_test_step"
        val outputsDir = testWorkspaceRoot.resolve("steps/$stepDir/outputs")
        Files.createDirectories(outputsDir)

        // Create multiple output files
        listOf(outputId1, outputId2, outputId3).forEachIndexed { index, outputId ->
            outputsDir.resolve("output${index + 1}.csv").writeText("output for $outputId")
        }

        val mockStore = MockArtefactStore()
        val step = PlannedStep(
            metadata = StepMetadata(
                id = stepId,
                name = "test-step"
            ),
            process = CommandSpec("echo", listOf(ExpandedArg.Literal("test"))),
            bindings = ResolvedBindings(
                outputs = mapOf(
                    outputId1 to createResolvedOutput(
                        id = outputId1,
                        resolvedPath = "steps/$stepDir/outputs/output1.csv"
                    ),
                    outputId2 to createResolvedOutput(
                        id = outputId2,
                        resolvedPath = "steps/$stepDir/outputs/output2.csv"
                    ),
                    outputId3 to createResolvedOutput(
                        id = outputId3,
                        resolvedPath = "steps/$stepDir/outputs/output3.csv"
                    )
                )
            ),
            environmentRef = UUID.randomUUID()
        )

        val result = runner(
            exitCode = 0,
            artefactStore = mockStore
        ).run(step, createExecutionWorkspace(listOf(step)))

        assertEquals(ExecutionStatus.SUCCEEDED, result.status)
        assertEquals(3, mockStore.recorded.size)
        assertNotNull(result.outputs)
        assertEquals(3, result.outputs!!.size)

        val recordedOutputIds = mockStore.recorded.map { it.outputId }.toSet()
        assertTrue(recordedOutputIds.contains(outputId1))
        assertTrue(recordedOutputIds.contains(outputId2))
        assertTrue(recordedOutputIds.contains(outputId3))
    }

    @Test
    fun `missing output files are handled gracefully`() {
        val stepId = UUID.randomUUID()
        val outputId1 = UUID.randomUUID()
        val outputId2 = UUID.randomUUID()
        val stepDir = "01_test_step"
        val outputsDir = testWorkspaceRoot.resolve("steps/$stepDir/outputs")
        Files.createDirectories(outputsDir)

        // Create only one output file
        outputsDir.resolve("output1.csv").writeText("existing output")
        // output2 not created

        val mockStore = MockArtefactStore()
        val step = PlannedStep(
            metadata = StepMetadata(
                id = stepId,
                name = "test-step"
            ),
            process = CommandSpec("echo", listOf(ExpandedArg.Literal("test"))),
            bindings = ResolvedBindings(
                outputs = mapOf(
                    outputId1 to createResolvedOutput(
                        id = outputId1,
                        resolvedPath = "steps/$stepDir/outputs/output1.csv"
                    ),
                    outputId2 to createResolvedOutput(
                        id = outputId2,
                        resolvedPath = "steps/$stepDir/outputs/output2.csv"
                    )
                )
            ),
            environmentRef = UUID.randomUUID()
        )

        val result = runner(
            exitCode = 0,
            artefactStore = mockStore
        ).run(step, createExecutionWorkspace(listOf(step)))

        assertEquals(ExecutionStatus.SUCCEEDED, result.status)
        assertEquals(1, mockStore.recorded.size, "Only existing output should be recorded")
        assertEquals(outputId1, mockStore.recorded[0].outputId)
    }

    @Test
    fun `execution root is used as process working directory`() {
        val step = commandStep()
        val workspace = createExecutionWorkspace( listOf( step ) )
        val result = runner().run( step, workspace )

        // Working directory in the result detail should be the step's logical dir,
        // and execution should use executionRoot as the cwd (verified by artefact resolution)
        assertEquals( workspace.stepDir( step.metadata.id ), result.detail?.workingDirectory )
    }
    // -------------------------------------------------------------------------
    // CommandRunner invocation — executable, args, cwd
    // -------------------------------------------------------------------------

    @Test
    fun `runner is called with the exact executable from CommandSpec`() {
        val recording = RecordingCommandRunner(FixedCommandRunner())
        val step = commandStep(executable = "python", args = listOf("run.py"))
        CommandStepRunner(CapturingWorkspaceManager(testWorkspaceRoot.toString()), recording, MockArtefactStore()).run(step, createExecutionWorkspace(listOf(step)))

        assertEquals("python", recording.lastCommand?.executable)
    }

    @Test
    fun `runner is called with the exact args from CommandSpec`() {
        val recording = RecordingCommandRunner(FixedCommandRunner())
        val step = commandStep(executable = "python", args = listOf("run.py", "--verbose", "--output", "out.csv"))
        CommandStepRunner(CapturingWorkspaceManager(testWorkspaceRoot.toString()), recording, MockArtefactStore()).run(step, createExecutionWorkspace(listOf(step)))

        assertEquals(
            listOf("run.py", "--verbose", "--output", "out.csv").map { ExpandedArg.Literal(it) },
            recording.lastCommand?.args as List<ExpandedArg>
        )
    }

    @Test
    fun `detail workingDirectory is set to the step dir path from the workspace`() {
        val step = commandStep()
        val workspace = createExecutionWorkspace(listOf(step))
        val result = runner().run(step, workspace)

        // workingDirectory in detail must match the logical workspace-relative step dir
        assertEquals(workspace.stepDir(step.metadata.id), result.detail?.workingDirectory)
    }

    // Status mapping

    @Test
    fun `exit code 0 maps to SUCCEEDED`() {
        val step = commandStep()
        val result = runner(exitCode = 0).run(step, createExecutionWorkspace(listOf(step)))
        assertEquals(ExecutionStatus.SUCCEEDED, result.status)
    }

    @Test
    fun `non-zero exit code maps to FAILED`() {
        val step = commandStep()
        val result = runner(exitCode = 1).run(step, createExecutionWorkspace(listOf(step)))
        assertEquals(ExecutionStatus.FAILED, result.status)
    }

    @Test
    fun `timed-out result maps to FAILED`() {
        val step = commandStep()
        val result = runner(timedOut = true).run(step, createExecutionWorkspace(listOf(step)))
        assertEquals(ExecutionStatus.FAILED, result.status)
    }

    // StepFailure population

    @Test
    fun `successful step has no failure`() {
        val step = commandStep()
        val result = runner(exitCode = 0).run(step, createExecutionWorkspace(listOf(step)))
        assertNull(result.failure)
    }

    @Test
    fun `failed step carries COMMAND_FAILED failure kind`() {
        val step = commandStep()
        val result = runner(exitCode = 2).run(step, createExecutionWorkspace(listOf(step)))
        assertNotNull(result.failure)
        assertEquals(FailureKind.COMMAND_FAILED, result.failure!!.kind)
    }

    @Test
    fun `timed-out step carries TIMEOUT failure kind`() {
        val step = commandStep()
        val result = runner(timedOut = true).run(step, createExecutionWorkspace(listOf(step)))
        assertNotNull(result.failure)
        assertEquals(FailureKind.TIMEOUT, result.failure!!.kind)
    }

    // Exit code captured in detail

    @Test
    fun `exit code is captured in detail on success`() {
        val step = commandStep()
        val result = runner(exitCode = 0).run(step, createExecutionWorkspace(listOf(step)))
        assertEquals(0, result.detail?.exitCode)
    }

    @Test
    fun `non-zero exit code is captured in detail`() {
        val step = commandStep()
        val result = runner(exitCode = 127).run(step, createExecutionWorkspace(listOf(step)))
        assertEquals(ExecutionStatus.FAILED, result.status)
        assertEquals(127, result.detail?.exitCode)
    }

    @Test
    fun `timeout exit code is captured in detail`() {
        // JvmCommandRunner uses timeoutExitCode=-1 by default; FixedCommandRunner
        // returns whatever exitCode we set, so we simulate the timeout sentinel value.
        val step = commandStep()
        val result = runner(exitCode = -1, timedOut = true).run(step, createExecutionWorkspace(listOf(step)))
        assertEquals(ExecutionStatus.FAILED, result.status)
        assertEquals(FailureKind.TIMEOUT, result.failure!!.kind)
        assertEquals(-1, result.detail?.exitCode)
    }

    // StepRunDetail — command tokens

    @Test
    fun `detail contains command tokens`() {
        val step = commandStep(executable = "python", args = listOf("analyse.py", "--verbose"))
        val result = runner().run(step, createExecutionWorkspace(listOf(step)))
        assertEquals(listOf("python", "analyse.py", "--verbose"), result.detail?.command)
    }

    @Test
    fun `detail stdout ref is null when stdout is blank`() {
        val step = commandStep()
        val result = runner(stdout = "").run(step, createExecutionWorkspace(listOf(step)))
        assertNull(result.detail?.stdout)
    }

    @Test
    fun `detail stdout ref is populated when stdout is non-blank`() {
        val step = commandStep()
        val result = runner(stdout = "hello world").run(step, createExecutionWorkspace(listOf(step)))
        assertNotNull(result.detail?.stdout)
    }

    @Test
    fun `detail stderr ref is null when stderr is blank`() {
        val step = commandStep()
        val result = runner(stderr = "").run(step, createExecutionWorkspace(listOf(step)))
        assertNull(result.detail?.stderr)
    }

    @Test
    fun `detail stderr ref is null when stderr is non-blank`() {
        val step = commandStep()
        val result = runner(stderr = "error output").run(step, createExecutionWorkspace(listOf(step)))
        assertNull(result.detail?.stderr)
    }

    // -------------------------------------------------------------------------
    // Timestamps
    // -------------------------------------------------------------------------

    @Test
    fun `startedAt and finishedAt are populated`() {
        val step = commandStep()
        val result = runner().run(step, createExecutionWorkspace(listOf(step)))
        assertNotNull(result.startedAt)
        assertNotNull(result.finishedAt)
    }

    @Test
    fun `startedAt is not after finishedAt`() {
        val step = commandStep()
        val result = runner().run(step, createExecutionWorkspace(listOf(step)))
        val started = result.startedAt!!
        val finished = result.finishedAt!!
        assert(started <= finished) { "startedAt ($started) must not be after finishedAt ($finished)" }
    }

    // -------------------------------------------------------------------------
    // Unsupported process types
    // -------------------------------------------------------------------------

    @Test
    fun `InTasksRun step fails with INFRASTRUCTURE kind`() {
        val step = inProcessStep()
        val result = runner().run(step, createExecutionWorkspace(listOf(step)))
        assertEquals(ExecutionStatus.FAILED, result.status)
        assertNotNull(result.failure)
        assertEquals(FailureKind.INFRASTRUCTURE, result.failure!!.kind)
    }

    @Test
    fun `InTasksRun step failure message names the process type`() {
        val step = inProcessStep(name = "my-step")
        val result = runner().run(step, createExecutionWorkspace(listOf(step)))
        assertNotNull(result.failure)
        assert(result.failure!!.message.contains("InTasksRun")) {
            "Failure message should name the unsupported type; got: ${result.failure!!.message}"
        }
    }

    @Test
    fun recordsLogsWithStepExecution() {
        val mockLogRecorder = MockStepLogRecorder()
        val step = commandStep()
        val runner = CommandStepRunner(
            workspaceManager = CapturingWorkspaceManager(testWorkspaceRoot.toString()),
            commandRunner = FixedCommandRunner(stdout = "hello from step"),
            artefactStore = MockArtefactStore(),
            options = CommandStepRunner.Options(logRecorder = mockLogRecorder) // ← Inject mock
        )

        val result = runner.run(step, createExecutionWorkspace(listOf(step)))

        // Verify log recorder was called
        assertTrue(mockLogRecorder.wasCalled)
        assertNotNull(result.detail?.stdout)
        assertTrue(result.detail?.stdout?.value?.contains("logs/") ?: false)
    }

    @Test
    fun skipsLogRecordingWhenNoOutput() {
        val mockLogRecorder = MockStepLogRecorder()
        val step = commandStep()
        val runner = CommandStepRunner(
            workspaceManager = CapturingWorkspaceManager(testWorkspaceRoot.toString()),
            commandRunner = FixedCommandRunner(stdout = "", stderr = ""),
            artefactStore = MockArtefactStore(),
            options = CommandStepRunner.Options(logRecorder = mockLogRecorder)
        )

        runner.run(step, createExecutionWorkspace(listOf(step)))

        // Verify logs were skipped for empty output
        assertTrue(mockLogRecorder.recordsAreEmpty)
    }

    @Test
    fun usesLogRefInStepRunDetail() {
        val mockLogRecorder = MockStepLogRecorder()
        val step = commandStep()
        val runner = CommandStepRunner(
            workspaceManager = CapturingWorkspaceManager(testWorkspaceRoot.toString()),
            commandRunner = FixedCommandRunner(stdout = "stdout-content"),
            artefactStore = MockArtefactStore(),
            options = CommandStepRunner.Options(logRecorder = mockLogRecorder)
        )

        val result = runner.run(step, createExecutionWorkspace(listOf(step)))

        // Verify log ref is in result
        assertNotNull(result.detail?.stdout)
        assertEquals(ResourceKind.RELATIVE_PATH, result.detail?.stdout?.kind)
    }
}

private class MockStepLogRecorder : StepLogRecorder {
    var wasCalled = false
    var recordsAreEmpty = true

    override fun recordLogs(
        step: PlannedStep,
        result: CommandResult,
        workspace: ExecutionWorkspace
    ): ResourceRef? {
        wasCalled = true
        if (result.stdout.isBlank() && result.stderr.isBlank()) {
            recordsAreEmpty = true
            return null
        }
        recordsAreEmpty = false
        return ResourceRef(
            kind = ResourceKind.RELATIVE_PATH,
            value = "logs/${step.metadata.id}-test.log"
        )
    }
}
