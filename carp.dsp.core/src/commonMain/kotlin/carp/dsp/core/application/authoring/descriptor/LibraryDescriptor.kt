package carp.dsp.core.application.authoring.descriptor

import kotlinx.serialization.Serializable

/**
 * Publication metadata for a step in the shared step library.
 *
 * This metadata exists only on published `step.yaml` files. Researcher-authored
 * workflows do not include it. It describes where a step belongs in the library,
 * what it requires from its environment, which implementations it provides, the
 * method or algorithm it represents, and the fixture used for conformance checks.
 *
 * These fields were previously stored in a `library:` block that was parsed
 * separately by conformance tooling. By modelling them directly here, they
 * become a typed part of the descriptor that is parsed once and shared across
 * the conformance gate, resolver, and other tooling.
 *
 * @property tier Library tier: `core`, `sensing`, or `analysis`. Validated
 *   against the step's location and declared CARP data types.
 * @property subject Subject area within the tier, such as `heartrate` or `stats`.
 * @property environment Environment requirements for the step.
 * @property implementations Implementations provided by the step, typically one
 *   per language.
 * @property method Method or algorithm implemented by the step.
 * @property reference Fixture that all implementations must reproduce.
 */
@Serializable
data class LibraryDescriptor(
    val tier: String,
    val subject: String,
    val environment: LibraryEnvironmentDescriptor? = null,
    val implementations: List<ImplementationDescriptor> = emptyList(),
    val method: MethodDescriptor? = null,
    val reference: ReferenceFixtureDescriptor? = null,
)

/**
 * The environment a library step expects.
 *
 * @property default ID of the environment the step inlines and resolves to by
 *   default; must exist in the environment catalogue.
 * @property requires The constraints any substitute environment must satisfy, so
 *   a consumer can supply their own environment and still be compatible.
 */
@Serializable
data class LibraryEnvironmentDescriptor(
    val default: String,
    val requires: EnvironmentRequirementDescriptor? = null,
)

/**
 * Constraints on a substitute environment.
 *
 * @property kind Acceptable environment kinds, e.g. `["pixi", "conda"]`.
 * @property interpreter The language runtime the implementations need.
 * @property packages Packages the implementations import, with version ranges.
 */
@Serializable
data class EnvironmentRequirementDescriptor(
    val kind: List<String> = emptyList(),
    val interpreter: InterpreterRequirementDescriptor? = null,
    val packages: List<PackageRequirementDescriptor> = emptyList(),
)

/**
 * A required language runtime.
 *
 * @property name Interpreter name, e.g. `python`, `R`.
 * @property version Version range expression, e.g. `">=3.11,<4"`.
 */
@Serializable
data class InterpreterRequirementDescriptor(
    val name: String,
    val version: String? = null,
)

/**
 * A required package.
 *
 * @property name Package name as the environment manager knows it.
 * @property version Version range expression, e.g. `">=2.0"`.
 */
@Serializable
data class PackageRequirementDescriptor(
    val name: String,
    val version: String? = null,
)

/**
 * One implementation of the step.
 *
 * @property language Implementation language, e.g. `python`, `r`.
 * @property path Path to the entry point, relative to the step directory.
 */
@Serializable
data class ImplementationDescriptor(
    val language: String,
    val path: String,
)

/**
 * The method a step realizes, so a published step can be cited.
 *
 * @property name Human-readable method name.
 * @property citation Reference to the method paper, or `null` when the step
 *   implements a standard procedure with no source to cite.
 */
@Serializable
data class MethodDescriptor(
    val name: String,
    val citation: String? = null,
)

/**
 * The reference fixture an implementation must reproduce.
 *
 * Every implementation of a step, in any language, is expected to turn [input]
 * into [expected] within [tolerance] - this is what makes a multi-language step
 * verifiable rather than merely documented.
 *
 * @property input Fixture input, relative to the step directory. `null` for a
 *   step with no input to fix, such as a loader that fetches from the network.
 * @property expected The output the fixture must produce.
 * @property tolerance Permitted numeric deviation when comparing to [expected].
 */
@Serializable
data class ReferenceFixtureDescriptor(
    val input: String? = null,
    val expected: String? = null,
    val tolerance: ToleranceDescriptor? = null,
)

/**
 * Permitted numeric deviation when comparing against a reference fixture.
 *
 * @property float Relative tolerance for floating-point fields. Integer and
 *   categorical fields are compared exactly.
 */
@Serializable
data class ToleranceDescriptor(
    val float: Double? = null,
)
