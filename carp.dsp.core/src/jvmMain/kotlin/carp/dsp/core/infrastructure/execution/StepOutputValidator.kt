package carp.dsp.core.infrastructure.execution

import carp.dsp.core.application.execution.workspace.WorkspaceRefFactory
import dk.cachet.carp.analytics.application.execution.*
import dk.cachet.carp.analytics.application.plan.ResolvedBindings
import dk.cachet.carp.analytics.domain.data.FileLocation
import dk.cachet.carp.analytics.domain.workflow.StepMetadata
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.*

/**
 * Validates the output directory of a completed step against its declared outputs.
 *
 * This is a pure infrastructure component — all filesystem access is isolated here.
 * The core models ([ProducedOutputRef], [ExecutionIssue]) stay clean.
 *
 * Checks performed (subject to [OutputValidationPolicy]):
 * - **A — Missing declared outputs**: each declared output is expected as a file named
 *   `{outputId}` under `{outputsDir}/`. Missing files produce an [ExecutionIssueKind.OUTPUT_MISSING]
 *   warning, or a hard [FailureKind.OUTPUT_MISSING] failure when [OutputValidationPolicy.strictOutputs].
 * - **B — Unexpected outputs**: files found in the outputs directory that have no corresponding
 *   declared output produce an [ExecutionIssueKind.UNEXPECTED_OUTPUT] warning.
 * - **C — Metadata**: for every declared output that exists, `sizeBytes` and `sha256` are computed
 *   and stored on [ProducedOutputRef].
 *
 * @see OutputValidationPolicy
 */
object StepOutputValidator {

    private const val UNEXPECTED_OUTPUT_LIST_LIMIT = 20

    /**
     * Validates outputs for a completed step and returns a [ValidationResult].
     *
     * @param stepMetadata  The step whose outputs are being validated.
     * @param executionRoot Absolute path to the execution root (process working directory).
     *                      Declared output paths from bindings are resolved relative to this.
     * @param outputsDir    Absolute path to `{executionRoot}/steps/{stepMetadata}/outputs/` per
     *                      the workspace layout — used for unexpected-output scanning only.
     * @param bindings      The step's [ResolvedBindings]; `outputs` keys are the declared output IDs.
     * @param policy        Controls warning/failure behaviour.
     */
    fun validate(
        stepMetadata: StepMetadata,
        executionRoot: Path,
        outputsDir: Path,
        bindings: ResolvedBindings,
        policy: OutputValidationPolicy = OutputValidationPolicy.DEFAULT
    ): ValidationResult {
        val issues = mutableListOf<ExecutionIssue>()

        val (producedRefs, failDueToMissing) =
            checkDeclaredOutputs(stepMetadata, executionRoot, bindings, policy, issues)

        checkUnexpectedOutputs(stepMetadata, outputsDir, bindings, policy, issues)

        val failure = deriveFailure(failDueToMissing, outputsDir, bindings)
        val forcedStatus = if (failDueToMissing) ExecutionStatus.FAILED else null

        return ValidationResult(
            producedOutputRefs = producedRefs,
            issues = issues,
            failure = failure,
            forcedStatus = forcedStatus
        )
    }

    /**
     * Overload for tests and callers where files are placed directly under [outputsDir].
     * Uses [outputsDir] as both the execution root and outputs directory.
     */
    fun validate(
        stepMetadata: StepMetadata,
        outputsDir: Path,
        bindings: ResolvedBindings,
        policy: OutputValidationPolicy = OutputValidationPolicy.DEFAULT
    ): ValidationResult = validate(stepMetadata, outputsDir, outputsDir, bindings, policy)

    // ── A: Declared outputs ──────────────────────────────────────────────────

    private data class DeclaredOutputsResult(
        val producedRefs: List<ProducedOutputRef>,
        val failDueToMissing: Boolean
    )

    private fun checkDeclaredOutputs(
        stepMetadata: StepMetadata,
        executionRoot: Path,
        bindings: ResolvedBindings,
        policy: OutputValidationPolicy,
        issues: MutableList<ExecutionIssue>
    ): DeclaredOutputsResult {
        val producedRefs = mutableListOf<ProducedOutputRef>()
        var failDueToMissing = false

        for ((outputId, output) in bindings.outputs) {
            // Use the full resolved path from the binding — this matches what was passed to the
            // script as a command argument, so the file will be exactly here regardless of how
            // the workspace step-directory formatter names the folder.
            val resolvedPath = (output.location as FileLocation).path
            val fileName = Path(resolvedPath).fileName.toString()
            val expectedFile: Path = executionRoot.resolve(resolvedPath)
            val location = WorkspaceRefFactory.stepOutputRef(stepMetadata.id, fileName)


            if (expectedFile.exists() && expectedFile.isRegularFile()) {
                producedRefs += ProducedOutputRef(
                    outputId = outputId,
                    location = location,
                    sizeBytes = expectedFile.fileSize(),
                    sha256 = sha256Hex(expectedFile.readBytes())
                )
            } else {
                // Always record the ref (no metadata) so callers know what was expected
                producedRefs += ProducedOutputRef(outputId = outputId, location = location)

                if (policy.warnOnMissingDeclaredOutputs) {
                    issues += ExecutionIssue(
                        stepMetadata = stepMetadata,
                        kind = ExecutionIssueKind.OUTPUT_MISSING,
                        message = "Declared output missing: $fileName " +
                                "(outputId=$outputId), for step ${stepMetadata.name}"
                    )
                }
                if (policy.strictOutputs) failDueToMissing = true
            }
        }

        return DeclaredOutputsResult(producedRefs, failDueToMissing)
    }

    // ── B: Unexpected outputs ────────────────────────────────────────────────

    @OptIn(ExperimentalPathApi::class)
    private fun checkUnexpectedOutputs(
        stepMetadata: StepMetadata,
        outputsDir: Path,
        bindings: ResolvedBindings,
        policy: OutputValidationPolicy,
        issues: MutableList<ExecutionIssue>
    ) {
        if (!policy.warnOnUnexpectedOutputs || !outputsDir.exists() || !outputsDir.isDirectory()) return

        // Extract actual filenames from resolved locations (e.g., "output-txt.txt" instead of output ID)
        val declaredFileNames = bindings.outputs.values.map { output ->
            Path((output.location as FileLocation).path).fileName.toString()
        }.toSet()

        val unexpectedFiles = outputsDir.walk()
            .filter { it.isRegularFile() }
            .map { it.relativeTo(outputsDir).toString().replace("\\", "/") }
            .filter { it !in declaredFileNames }
            .sorted()
            .toList()

        if (unexpectedFiles.isEmpty()) return

        val listed = unexpectedFiles.take(UNEXPECTED_OUTPUT_LIST_LIMIT)
        val suffix = unexpectedFiles.size.let { total ->
            if (total > listed.size) " (+${total - listed.size} more)" else ""
        }
        issues += ExecutionIssue(
            stepMetadata = stepMetadata,
            kind = ExecutionIssueKind.UNEXPECTED_OUTPUT,
            message = "Unexpected output(s) found: ${listed.joinToString(", ")}$suffix"
        )
    }

    // ── Failure derivation ───────────────────────────────────────────────────

    private fun deriveFailure(
        failDueToMissing: Boolean,
        outputsDir: Path,
        bindings: ResolvedBindings
    ): StepFailure? {
        if (!failDueToMissing) return null
        val missingNames = bindings.outputs.keys
            .filter { !outputsDir.resolve(it.toString()).exists() }
            .joinToString(", ")
        return StepFailure(
            kind = FailureKind.OUTPUT_MISSING,
            message = "Step failed: missing declared output(s): $missingNames"
        )
    }

    // Helpers

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/**
 * The result of a [StepOutputValidator.validate] call.
 *
 * @param producedOutputRefs One entry per declared output, regardless of whether the file exists.
 * @param issues             Warnings (and possibly errors) about the output directory state.
 * @param failure            Non-null when [OutputValidationPolicy.strictOutputs] and at least one
 *                           declared output is missing.
 * @param forcedStatus       When non-null, the calling runner should override the step status to this value.
 */
data class ValidationResult(
    val producedOutputRefs: List<ProducedOutputRef>,
    val issues: List<ExecutionIssue>,
    val failure: StepFailure?,
    val forcedStatus: ExecutionStatus?
)

