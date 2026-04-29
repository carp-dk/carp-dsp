package carp.dsp.core.application.translation.cwl

/**
 * A CWL CommandLineTool document for a single DSP step.
 *
 * @property stepId  The DSP step ID this document was translated from.
 * @property content CWL YAML string (cwlVersion: v1.2).
 * @property toolVersion CWL version declared in the document.
 */
data class CwlDocument(
    val stepId: String,
    val content: String,
    val toolVersion: String = "v1.2",
)
