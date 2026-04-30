package carp.dsp.core.application.translation.snakemake

/**
 * A Snakemake workflow produced by [DspToSnakemakeExporter].
 *
 * @property content The full Snakefile text, ready to write to disk.
 * @property envFiles Conda environment files to write alongside the Snakefile.
 *   Keys are relative paths (e.g. `"envs/my-env.yaml"`); values are YAML content.
 *   Rules referencing a conda/pixi environment emit `conda: "<key>"` pointing here.
 *   Empty when no conda/pixi environments are declared.
 */
data class SnakemakeWorkflow(
    val content: String,
    val envFiles: Map<String, String> = emptyMap(),
)
