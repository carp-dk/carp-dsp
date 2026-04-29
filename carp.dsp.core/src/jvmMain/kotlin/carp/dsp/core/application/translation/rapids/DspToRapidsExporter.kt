package carp.dsp.core.application.translation.rapids

import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor
import carp.dsp.core.application.translation.WorkflowExporter
import carp.dsp.core.application.translation.snakemake.DspToSnakemakeExporter

/**
 * Exports a [WorkflowDescriptor] to a [RapidsWorkflow].
 *
 * Delegates Snakefile generation to [DspToSnakemakeExporter] and wraps it
 * with the RAPIDS container image. This reflects how RAPIDS runs workflows:
 * Snakemake inside a Docker container.
 */
object DspToRapidsExporter : WorkflowExporter<RapidsWorkflow> {

    const val DEFAULT_CONTAINER_IMAGE = "moshiresearch/rapids:latest"

    override fun export(descriptor: WorkflowDescriptor): RapidsWorkflow =
        RapidsWorkflow(
            snakemake = DspToSnakemakeExporter.export(descriptor),
            containerImage = DEFAULT_CONTAINER_IMAGE,
        )
}
