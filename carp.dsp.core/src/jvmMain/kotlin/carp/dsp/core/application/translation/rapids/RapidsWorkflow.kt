package carp.dsp.core.application.translation.rapids

import carp.dsp.core.application.translation.snakemake.SnakemakeWorkflow

/**
 * A RAPIDS workflow produced by [DspToRapidsExporter].
 *
 * RAPIDS extends Snakemake — the [snakemake] field holds the underlying
 * Snakefile and [containerImage] specifies the Docker image used to run it.
 *
 * @property snakemake    The Snakemake workflow (Snakefile content).
 * @property containerImage Docker image used to execute the workflow on RAPIDS.
 */
data class RapidsWorkflow(
    val snakemake: SnakemakeWorkflow,
    val containerImage: String,
)
