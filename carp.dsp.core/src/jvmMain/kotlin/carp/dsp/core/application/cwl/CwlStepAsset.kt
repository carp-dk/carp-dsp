package carp.dsp.core.application.cwl

import health.workflows.interfaces.model.CwlTranslationAsset

/**
 * A CWL CommandLineTool document for a single DSP step, produced by [DspToCwlTranslator].
 *
 * This is distinct from [health.workflows.interfaces.model.CwlTranslationAsset], which
 * represents the whole-workflow CWL asset stored in a [health.workflows.interfaces.model.WorkflowArtifactPackage].
 * Use [toPackageAsset] to convert when wiring into [carp.dsp.core.application.packaging.PackageBuilder].
 *
 * @property stepId The DSP step ID this tool was translated from.
 * @property content CWL YAML string, valid for cwlVersion v1.2.
 * @property toolVersion CWL version declared in the document (always "v1.2" for R1).
 */
data class CwlStepAsset(
    val stepId: String,
    val content: String,
    val toolVersion: String = "v1.2",
) {
    /** Convert to the interface-library asset type for use in [carp.dsp.core.application.packaging.PackageBuilder]. */
    fun toPackageAsset(): CwlTranslationAsset =
        CwlTranslationAsset(content = content, toolVersion = toolVersion)
}
