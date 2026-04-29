package carp.dsp.core.application.translation

import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor

/**
 * Converts an external workflow format [F] back into a [WorkflowDescriptor].
 *
 * Importers are inherently best-effort: not all external formats carry the same
 * semantic richness as a DSP descriptor. Implementations should document what
 * information can and cannot be recovered.
 *
 * Not all formats have an importer — see individual sub-packages.
 */
fun interface WorkflowImporter<in F> {
    fun import(source: F): WorkflowDescriptor
}
