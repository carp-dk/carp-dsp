package carp.dsp.core.application.authoring.descriptor

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── PythonEntryPointDescriptor ────────────────────────────────────────────────

/**
 * Sealed descriptor for a Python entry point.
 *
 * Mirrors [dk.cachet.carp.analytics.domain.tasks.PythonEntryPoint] using plain strings.
 *
 * Variants:
 * - [ScriptEntryPointDescriptor] — run a `.py` file (`python <scriptPath> …`).
 * - [ModuleEntryPointDescriptor] — run a module (`python -m <moduleName> …`).
 */
@Serializable
sealed interface PythonEntryPointDescriptor

/** Run a Python script at [scriptPath]. Maps to [dk.cachet.carp.analytics.domain.tasks.Script]. */
@Serializable
@SerialName("script")
data class ScriptEntryPointDescriptor( val scriptPath: String ) : PythonEntryPointDescriptor

/** Run a Python module by [moduleName] (`python -m <moduleName>`). Maps to [dk.cachet.carp.analytics.domain.tasks.Module]. */
@Serializable
@SerialName("module")
data class ModuleEntryPointDescriptor( val moduleName: String ) : PythonEntryPointDescriptor

// ── TaskDescriptor ────────────────────────────────────────────────────────────

/**
 * Sealed descriptor for the unit of work a step performs.
 *
 * Mirrors the [dk.cachet.carp.analytics.domain.tasks.TaskDefinition] hierarchy but
 * uses plain strings throughout so the descriptor is YAML-friendly and decoupled from
 * domain identity types.
 *
 * Variants:
 * - [CommandTaskDescriptor] — arbitrary external-process invocation.
 * - [PythonTaskDescriptor] — Python script or module execution.
 * - [InProcessTaskDescriptor] — operation executed inside the CARP-DSP host process.
 */
@Serializable
sealed interface TaskDescriptor
{
    /**
     * Stable UUID string identifying this task definition within the step.
     *
     * **Lenient read:** `null` is accepted from YAML; the importer generates a UUID.
     * **Strict write:** exporters always emit a concrete, non-null value.
     */
    val id: String?

    /** Human-readable name for the task. Required — the primary author-facing identifier. */
    val name: String

    /** Optional description. */
    val description: String?
}

// ── CommandTaskDescriptor ─────────────────────────────────────────────────────

/**
 * Descriptor for an arbitrary external-process invocation.
 *
 * Maps to [dk.cachet.carp.analytics.domain.tasks.CommandTaskDefinition].
 *
 * @property id Optional stable UUID. Omit in authored YAML; the importer generates one.
 * @property executable The program to run (e.g. `"cp"`, `"Rscript"`).
 * @property args Structured argument list — each element is a string argument token.
 */
@Serializable
@SerialName("command")
data class CommandTaskDescriptor(
    override val id: String? = null,
    override val name: String,
    override val description: String? = null,
    val executable: String,
    val args: List<String> = emptyList(),
) : TaskDescriptor

// ── PythonTaskDescriptor ──────────────────────────────────────────────────────

/**
 * Descriptor for a Python script or module execution.
 *
 * Maps to [dk.cachet.carp.analytics.domain.tasks.PythonTaskDefinition].
 *
 * @property id Optional stable UUID. Omit in authored YAML; the importer generates one.
 * @property entryPoint How to invoke Python — either run a [ScriptEntryPointDescriptor]
 *   or a [ModuleEntryPointDescriptor].
 * @property args Structured argument list — each element is a string argument token.
 */
@Serializable
@SerialName("python")
data class PythonTaskDescriptor(
    override val id: String? = null,
    override val name: String,
    override val description: String? = null,
    val entryPoint: PythonEntryPointDescriptor,
    val args: List<String> = emptyList(),
) : TaskDescriptor

// ── InProcessTaskDescriptor ───────────────────────────────────────────────────

/**
 * Descriptor for an operation executed inside the CARP-DSP host process.
 *
 * Maps to [dk.cachet.carp.analytics.application.plan.InTasksRun] at plan time.
 *
 * @property id Optional stable UUID. Omit in authored YAML; the importer generates one.
 * @property operationId Registered operation identifier (must not be blank).
 * @property parameters Arbitrary string parameters forwarded to the operation.
 */
@Serializable
@SerialName("in-process")
data class InProcessTaskDescriptor(
    override val id: String? = null,
    override val name: String,
    override val description: String? = null,
    val operationId: String,
    val parameters: Map<String, String> = emptyMap(),
) : TaskDescriptor
