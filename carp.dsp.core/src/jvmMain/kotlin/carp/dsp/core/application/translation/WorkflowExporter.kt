package carp.dsp.core.application.translation

import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor

/**
 * Converts a [WorkflowDescriptor] into an external workflow format [F].
 *
 * Each supported format provides its own implementation:
 * - [carp.dsp.core.application.translation.cwl.DspToCwlExporter]
 * - [carp.dsp.core.application.translation.snakemake.DspToSnakemakeExporter]
 * - [carp.dsp.core.application.translation.rapids.DspToRapidsExporter]
 */
fun interface WorkflowExporter<out F> {
    fun export(descriptor: WorkflowDescriptor): F
}
