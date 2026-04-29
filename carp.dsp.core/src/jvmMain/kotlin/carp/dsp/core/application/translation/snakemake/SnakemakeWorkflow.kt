package carp.dsp.core.application.translation.snakemake

/**
 * A Snakemake workflow produced by [DspToSnakemakeExporter].
 *
 * @property content The full Snakefile text, ready to write to disk.
 */
data class SnakemakeWorkflow(val content: String)
