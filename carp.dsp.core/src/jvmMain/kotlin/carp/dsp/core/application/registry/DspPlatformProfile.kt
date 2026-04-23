package carp.dsp.core.application.registry

import health.workflows.interfaces.api.PlatformConstraints
import health.workflows.interfaces.api.PlatformProfile
import health.workflows.interfaces.model.EnvironmentType
import health.workflows.interfaces.model.ScriptLanguage
import health.workflows.interfaces.model.WorkflowFormat

/**
 * The CARP-DSP platform's declared capabilities.
 *
 * Used as the default profile when checking whether an incoming workflow
 * package is compatible with this platform.
 *
 * getDOI and getLineage are excluded from supportedOperations — they are stubbed.
 */
val DspPlatformProfile = PlatformProfile(
    platformId = "carp-dsp",
    supportedFormats = listOf(WorkflowFormat.CARP_DSP, WorkflowFormat.CWL),
    supportedEnvironments = listOf(
        EnvironmentType.CONDA,
        EnvironmentType.PIXI,
        EnvironmentType.R,
        EnvironmentType.SYSTEM,
    ),
    supportedOperations = listOf(
        "getComponent",
        "search",
        "publish",
        "resolveDependencies",
        "checkCompatibility",
    ),
    constraints = PlatformConstraints(
        maxDependencyDepth = 3,
        requiresDOI = false,
        supportedScriptLanguages = listOf(
            ScriptLanguage.PYTHON,
            ScriptLanguage.R,
            ScriptLanguage.BASH,
            ScriptLanguage.SHELL,
        ),
    ),
)
