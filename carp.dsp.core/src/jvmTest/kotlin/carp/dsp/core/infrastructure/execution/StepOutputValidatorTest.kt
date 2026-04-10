package carp.dsp.core.infrastructure.execution

import carp.dsp.core.application.execution.workspace.WorkspaceRefFactory
import carp.dsp.core.application.plan.createResolvedOutput
import dk.cachet.carp.analytics.application.execution.ExecutionIssueKind
import dk.cachet.carp.analytics.application.execution.ExecutionStatus
import dk.cachet.carp.analytics.application.execution.FailureKind
import dk.cachet.carp.analytics.application.plan.ResolvedBindings
import dk.cachet.carp.analytics.domain.workflow.StepMetadata
import dk.cachet.carp.common.application.UUID
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class StepOutputValidatorTest {

    private lateinit var tempRoot: Path
    private lateinit var outputsDir: Path
    private lateinit var stepDir: Path

    @BeforeTest
    fun setUp() {
        tempRoot = Files.createTempDirectory("step-output-validator-test")
        outputsDir = tempRoot.resolve("outputs").createDirectories()
        stepDir = tempRoot.resolve("step").createDirectories()
    }

    @AfterTest
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    // Helpers

    private fun freshOutputsDir(): Path =
        tempRoot.resolve("outputs-${UUID.randomUUID()}").createDirectories()

    private fun bindings(vararg outputIds: UUID): ResolvedBindings =
        ResolvedBindings(
            outputs = outputIds.associateWith { id -> createResolvedOutput(id, "text/plain", path = "$id.txt") }
        )

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    // A — Declared output exists

    @Test
    fun `declared output exists - no warning issued`() {
        val outputId = UUID.randomUUID()
        val dir = freshOutputsDir()
        val fileName = "$outputId.txt"
        dir.resolve(fileName).createFile()

        val result = StepOutputValidator.validate(
            stepMetadata = StepMetadata(
                id = outputId,
                name = "Test Step"
            ),
            outputsDir = dir,
            bindings = bindings(outputId)
        )

        assertTrue(result.issues.none { it.kind == ExecutionIssueKind.OUTPUT_MISSING })
    }

    @Test
    fun `declared output exists - ProducedOutputRef location uses RELATIVE_PATH via WorkspaceRefFactory`() {
        val stepId = UUID.randomUUID()
        val outputId = UUID.randomUUID()
        val dir = freshOutputsDir()
        val fileName = "$outputId.txt"
        dir.resolve(fileName).createFile()

        val result = StepOutputValidator.validate(
            stepMetadata = StepMetadata(
                id = stepId, name = "Test Step"
            ),
                dir, bindings(outputId)
        )

        val ref = result.producedOutputRefs.single()
        val expected = WorkspaceRefFactory.stepOutputRef(stepId, fileName)
        assertEquals(expected, ref.location)
    }

    @Test
    fun `declared output exists - sizeBytes is populated`() {
        val outputId = UUID.randomUUID()
        val content = "hello world".toByteArray()
        val dir = freshOutputsDir()
        val fileName = "$outputId.txt"
        dir.resolve(fileName).writeBytes(content)

        val result = StepOutputValidator.validate(
            stepMetadata = StepMetadata(
                id = outputId, name = "Test Step"
            ),
                dir, bindings(outputId)
        )

        assertEquals(content.size.toLong(), result.producedOutputRefs.single().sizeBytes)
    }

    @Test
    fun `declared output exists - sha256 is populated and correct`() {
        val outputId = UUID.randomUUID()
        val content = "sha256 test content".toByteArray()
        val dir = freshOutputsDir()
        val fileName = "$outputId.txt"
        dir.resolve(fileName).writeBytes(content)

        val result = StepOutputValidator.validate(
            StepMetadata(
            id = outputId, name = "Test Step"
        ),
            dir, bindings(outputId)
        )

        assertEquals(sha256Hex(content), result.producedOutputRefs.single().sha256)
    }

    @Test
    fun `declared output exists - forcedStatus is null`() {
        val outputId = UUID.randomUUID()
        val dir = freshOutputsDir()
        dir.resolve(outputId.toString()).createFile()

        val result = StepOutputValidator.validate(
            StepMetadata(
            id = outputId, name = "Test Step"
        ),
            dir, bindings(outputId)
        )

        assertNull(result.forcedStatus)
        assertNull(result.failure)
    }

    // -------------------------------------------------------------------------
    // A — Declared output missing (default policy = warn, not strict)
    // -------------------------------------------------------------------------

    @Test
    fun `declared output missing - warning issue emitted`() {
        val outputId = UUID.randomUUID()
        val dir = freshOutputsDir()
        // deliberately do NOT create the file

        val result = StepOutputValidator.validate(
            StepMetadata(
                id = outputId, name = "Test Step"
            ),
            outputsDir = dir,
            bindings = bindings(outputId)
        )

        val issue = result.issues.singleOrNull { it.kind == ExecutionIssueKind.OUTPUT_MISSING }
        assertNotNull(issue, "Expected an OUTPUT_MISSING issue")
        assertTrue(issue.message.contains(outputId.toString()))
    }

    @Test
    fun `declared output missing - step is NOT failed under default policy`() {
        val outputId = UUID.randomUUID()
        val dir = freshOutputsDir()

        val result = StepOutputValidator.validate(
            StepMetadata(
            id = outputId, name = "Test Step"
        ),
            dir, bindings(outputId)
        )

        assertNull(result.forcedStatus, "Default policy must not force FAILED status")
        assertNull(result.failure)
    }

    @Test
    fun `declared output missing - ProducedOutputRef still emitted with correct location`() {
        val stepId = UUID.randomUUID()
        val outputId = UUID.randomUUID()
        val dir = freshOutputsDir()

        val result = StepOutputValidator.validate(
            StepMetadata(
            id = stepId, name = "Test Step"
        ),
            dir, bindings(outputId)
        )

        val ref = result.producedOutputRefs.singleOrNull()
        assertNotNull(ref)
        val expectedFileName = "$outputId.txt"
        val expected = WorkspaceRefFactory.stepOutputRef(stepId, expectedFileName)
        assertEquals(expected, ref.location)
        assertNull(ref.sizeBytes)
        assertNull(ref.sha256)
    }

    @Test
    fun `declared output missing - strictOutputs=true forces FAILED with OUTPUT_MISSING kind`() {
        val outputId = UUID.randomUUID()
        val dir = freshOutputsDir()

        val result = StepOutputValidator.validate(
            StepMetadata(
                id = outputId, name = "Test Step"
            ),
            outputsDir = dir,
            bindings = bindings(outputId),
            policy = OutputValidationPolicy(strictOutputs = true)
        )

        assertEquals(ExecutionStatus.FAILED, result.forcedStatus)
        assertNotNull(result.failure)
        assertEquals(FailureKind.OUTPUT_MISSING, checkNotNull(result.failure).kind)
    }

    @Test
    fun `declared output missing - warnOnMissingDeclaredOutputs=false suppresses issue`() {
        val outputId = UUID.randomUUID()
        val dir = freshOutputsDir()

        val result = StepOutputValidator.validate(
            StepMetadata(
                id = outputId, name = "Test Step"
            ),
            outputsDir = dir,
            bindings = bindings(outputId),
            policy = OutputValidationPolicy(warnOnMissingDeclaredOutputs = false)
        )

        assertTrue(result.issues.none { it.kind == ExecutionIssueKind.OUTPUT_MISSING })
    }

    // B — Unexpected outputs

    @Test
    fun `unexpected file in outputs dir - warning issue emitted`() {
        val dir = freshOutputsDir()
        dir.resolve("unexpected.csv").createFile()

        val result = StepOutputValidator.validate(
            StepMetadata(
                id = UUID.randomUUID(), name = "Test Step"
            ),
            outputsDir = dir,
            bindings = ResolvedBindings()
        )

        val issue = result.issues.singleOrNull { it.kind == ExecutionIssueKind.UNEXPECTED_OUTPUT }
        assertNotNull(issue, "Expected an UNEXPECTED_OUTPUT issue")
        assertTrue(issue.message.contains("unexpected.csv"))
    }

    @Test
    fun `unexpected outputs - step is NOT failed`() {
        val dir = freshOutputsDir()
        dir.resolve("extra.txt").createFile()

        val result = StepOutputValidator.validate(
            StepMetadata(
                id = UUID.randomUUID(), name = "Test Step"
            ),
            outputsDir = dir,
            bindings = ResolvedBindings()
        )

        assertNull(result.forcedStatus)
    }

    @Test
    fun `unexpected outputs warning lists names in sorted order`() {
        val dir = freshOutputsDir()
        dir.resolve("z-file.txt").createFile()
        dir.resolve("a-file.txt").createFile()
        dir.resolve("m-file.txt").createFile()

        val result = StepOutputValidator.validate(
            StepMetadata(
                id = UUID.randomUUID(), name = "Test Step"
            ),
            outputsDir = dir,
            bindings = ResolvedBindings()
        )

        val issue = result.issues.single { it.kind == ExecutionIssueKind.UNEXPECTED_OUTPUT }
        val msgIndex = { name: String -> issue.message.indexOf(name) }
        assertTrue(msgIndex("a-file.txt") < msgIndex("m-file.txt"))
        assertTrue(msgIndex("m-file.txt") < msgIndex("z-file.txt"))
    }

    @Test
    fun `unexpected outputs - warnOnUnexpectedOutputs=false suppresses issue`() {
        val dir = freshOutputsDir()
        dir.resolve("extra.bin").createFile()

        val result = StepOutputValidator.validate(
            StepMetadata(
                id = UUID.randomUUID(), name = "Test Step"
            ),
            outputsDir = dir,
            bindings = ResolvedBindings(),
            policy = OutputValidationPolicy(warnOnUnexpectedOutputs = false)
        )

        assertTrue(result.issues.none { it.kind == ExecutionIssueKind.UNEXPECTED_OUTPUT })
    }

    @Test
    fun `unexpected outputs - declared output files are not reported as unexpected`() {
        val outputId = UUID.randomUUID()
        val dir = freshOutputsDir()
        val fileName = "$outputId.txt"
        dir.resolve(fileName).createFile()

        val result = StepOutputValidator.validate(
            StepMetadata(
                id = UUID.randomUUID(), name = "Test Step"
            ),
            outputsDir = dir,
            bindings = bindings(outputId)
        )

        assertTrue(result.issues.none { it.kind == ExecutionIssueKind.UNEXPECTED_OUTPUT })
    }

    @Test
    fun `unexpected outputs only inspects outputs dir not the entire step dir`() {
        val isolatedStepDir = tempRoot.resolve("isolated-step").createDirectories()
        val isolatedOutputsDir = isolatedStepDir.resolve("outputs").createDirectories()
        val logsDir = isolatedStepDir.resolve("logs").createDirectories()
        logsDir.resolve("run.log").createFile() // must NOT be seen by validator

        val result = StepOutputValidator.validate(
            StepMetadata(
                id = UUID.randomUUID(), name = "Test Step"
            ),
            outputsDir = isolatedOutputsDir,
            bindings = ResolvedBindings()
        )

        assertTrue(
            result.issues.none { it.kind == ExecutionIssueKind.UNEXPECTED_OUTPUT },
            "Validator must not report files outside the outputs directory"
        )
    }

    // WorkspaceRefFactory — path traversal regression

    @Test
    fun `WorkspaceRefFactory rejects absolute path`() {
        val stepId = UUID.randomUUID()
        var threw = false
        try { WorkspaceRefFactory.stepOutputRef(stepId, "/etc/passwd") }
        catch (_: IllegalArgumentException) { threw = true }
        assertTrue(threw, "WorkspaceRefFactory must reject absolute paths")
    }

    @Test
    fun `WorkspaceRefFactory rejects path traversal`() {
        val stepId = UUID.randomUUID()
        var threw = false
        try { WorkspaceRefFactory.stepOutputRef(stepId, "../../../secret") }
        catch (_: IllegalArgumentException) { threw = true }
        assertTrue(threw, "WorkspaceRefFactory must reject '..' path traversal")
    }

    @Test
    fun `WorkspaceRefFactory rejects Windows drive prefix`() {
        val stepId = UUID.randomUUID()
        var threw = false
        try { WorkspaceRefFactory.stepOutputRef(stepId, "C:\\secret") }
        catch (_: IllegalArgumentException) { threw = true }
        assertTrue(threw, "WorkspaceRefFactory must reject paths containing ':'")
    }

    // Overflow cap — large number of unexpected files

    @Test
    fun `unexpected output list is capped and remainder noted`() {
        val dir = freshOutputsDir()
        repeat(25) { i -> dir.resolve("file-%02d.txt".format(i)).createFile() }

        val result = StepOutputValidator.validate(
            StepMetadata(
                id = UUID.randomUUID(), name = "Test Step"
            ),
            outputsDir = dir,
            bindings = ResolvedBindings()
        )

        val issue = result.issues.single { it.kind == ExecutionIssueKind.UNEXPECTED_OUTPUT }
        assertTrue(
            issue.message.contains("+5 more"),
            "Expected '+5 more' in message but got: ${issue.message}"
        )
    }
}
