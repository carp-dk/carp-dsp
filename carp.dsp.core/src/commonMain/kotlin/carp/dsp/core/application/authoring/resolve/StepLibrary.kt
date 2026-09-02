package carp.dsp.core.application.authoring.resolve

import carp.dsp.core.application.authoring.descriptor.DefinedStepDescriptor
import carp.dsp.core.application.authoring.descriptor.EnvironmentDescriptor

/**
 * A library step looked up for resolution: its single defined step and the
 * environments that step needs.
 *
 * @property version The concrete version resolved (a bare reference resolves to
 *   the latest; this records which that was).
 * @property step The library step as a self-contained [DefinedStepDescriptor].
 * @property environments Environments the step references, merged into the
 *   consuming workflow during resolution.
 * @property contentHash Hash of the step's published content, used to pin the
 *   resolution in `steps.lock` and detect the library serving different bytes
 *   under the same version.
 * @property implFiles The step's implementation files, keyed by their path
 *   relative to the step directory (e.g. `"impl/python/clean.py"`).
 */
data class LibraryStep(
    val version: String,
    val step: DefinedStepDescriptor,
    val environments: Map<String, EnvironmentDescriptor> = emptyMap(),
    val contentHash: String,
    val implFiles: Map<String, String> = emptyMap(),
)

/**
 * Looks up a library step by reference, decoupling [UsesResolver] from where the
 * library actually lives (vendored on disk, a registry, or a test fake) - the
 * same pattern as `ProtocolDataTypeProvider`.
 */
fun interface StepLibrary
{
    /**
     * Returns the library step published under [id] at [version], or `null` when
     * no such step is known. A `null` [version] resolves to the latest.
     */
    fun lookup(id: String, version: String?): LibraryStep?
}
