package carp.dsp.core.application.translation.snakemake

import carp.dsp.core.application.authoring.descriptor.CommandTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.DefinedStepDescriptor
import carp.dsp.core.application.authoring.descriptor.EnvironmentDescriptor
import carp.dsp.core.application.authoring.descriptor.FileInputSource
import carp.dsp.core.application.authoring.descriptor.InProcessTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.ModuleEntryPointDescriptor
import carp.dsp.core.application.authoring.descriptor.PythonTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.RTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.ScriptEntryPointDescriptor
import carp.dsp.core.application.authoring.descriptor.StepDescriptor
import carp.dsp.core.application.authoring.descriptor.StepOutputInputSource
import carp.dsp.core.application.authoring.descriptor.TaskDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor
import carp.dsp.core.application.translation.WorkflowExporter

/**
 * Exports a [WorkflowDescriptor] to a [SnakemakeWorkflow].
 *
 * Each DSP step becomes one Snakemake rule. Input/output port connections
 * are resolved to concrete filenames. A `rule all` is prepended to declare
 * the final step's outputs as pipeline targets.
 *
 * Environment handling:
 * - `docker` → `container: "docker://<image>"` directive on the rule
 * - `conda` / `pixi` → `conda: "envs/<envId>.yaml"` directive on the rule;
 *    a matching `environment.yaml` is generated in [SnakemakeWorkflow.envFiles]
 * - `system` / `r` → no directive; runner uses whatever is on PATH
 */
object DspToSnakemakeExporter : WorkflowExporter<SnakemakeWorkflow> {

    private val CONDA_KINDS = setOf("conda", "pixi")

    override fun export(descriptor: WorkflowDescriptor): SnakemakeWorkflow {
        val envFiles = buildEnvFiles(descriptor)
        val content = buildString {
            val outputFiles = buildOutputFileMap(descriptor)

            val finalOutputs = (descriptor.steps.last() as? DefinedStepDescriptor)?.outputs.orEmpty()
                .mapNotNull { port -> outputFiles[port.id] }

            appendLine("rule all:")
            appendLine("    input:")
            finalOutputs.forEach { f -> appendLine("        \"$f\",") }
            appendLine()

            descriptor.steps.forEach { step -> appendRule(step, descriptor.environments, outputFiles) }
        }
        return SnakemakeWorkflow(content = content, envFiles = envFiles)
    }

    // -- Conda env file generation -------------------------------------------------

    /**
     * Generates one `environment.yaml` per conda/pixi environment in the descriptor.
     * Keys are relative paths suitable for the `conda:` directive (e.g. `"envs/env1.yaml"`).
     */
    private fun buildEnvFiles(descriptor: WorkflowDescriptor): Map<String, String> =
        descriptor.environments
            .filter { (_, env) -> env.kind in CONDA_KINDS }
            .mapKeys { (envId, _) -> "envs/$envId.yaml" }
            .mapValues { (_, env) -> buildCondaEnvYaml(env) }

    private fun buildCondaEnvYaml(env: EnvironmentDescriptor): String = buildString {
        appendLine("name: ${env.name}")

        val channels = env.spec["channels"]?.takeIf { it.isNotEmpty() }
            ?: listOf("conda-forge", "defaults")
        appendLine("channels:")
        channels.forEach { appendLine("  - $it") }

        appendLine("dependencies:")
        // Pin the language version as the first dependency
        env.spec["pythonVersion"]?.firstOrNull()?.let { appendLine("  - python=$it") }
        env.spec["rVersion"]?.firstOrNull()?.let { appendLine("  - r-base=$it") }
        env.spec["dependencies"]?.forEach { appendLine("  - $it") }
    }.trimEnd()

    // -- Snakefile rule builder ---------------------------------------------------

    private fun buildOutputFileMap(descriptor: WorkflowDescriptor): Map<String, String> =
        buildMap {
            descriptor.steps.filterIsInstance<DefinedStepDescriptor>().forEach { step ->
                step.outputs.forEach { port ->
                    val id = port.id ?: return@forEach
                    put(id, portToFilename(id, port.descriptor?.fileFormat))
                }
            }
        }

    private fun StringBuilder.appendRule(
        step: StepDescriptor,
        environments: Map<String, EnvironmentDescriptor>,
        outputFiles: Map<String, String>,
    ) {
        // A referenced step (`uses:`) has no inline task until resolved; skip it.
        val defined = step as? DefinedStepDescriptor ?: return
        val ruleName = (step.id ?: defined.task.name)
            .replace(Regex("[^a-zA-Z0-9]+"), "_")
            .trim('_')

        val env = environments[defined.environmentId]
        val dockerImage = if (env?.kind == "docker") env.spec["image"]?.firstOrNull() else null
        val condaFile = if (env?.kind in CONDA_KINDS) "envs/${defined.environmentId}.yaml" else null

        val inputFiles = defined.inputs.mapNotNull { port ->
            when (val src = port.source) {
                is StepOutputInputSource -> outputFiles[src.outputId]
                is FileInputSource -> src.path
                else -> null
            }
        }
        val outputFilesList = defined.outputs.mapNotNull { port -> outputFiles[port.id] }
        val shellCmd = buildShellCommand(defined.task, inputFiles.size, outputFilesList.size)

        appendLine("rule $ruleName:")
        if (inputFiles.isNotEmpty()) {
            appendLine("    input:")
            inputFiles.forEach { f -> appendLine("        \"$f\",") }
        }
        if (outputFilesList.isNotEmpty()) {
            appendLine("    output:")
            outputFilesList.forEach { f -> appendLine("        \"$f\",") }
        }
        if (dockerImage != null) {
            appendLine("    container: \"docker://$dockerImage\"")
        }
        if (condaFile != null) {
            appendLine("    conda: \"$condaFile\"")
        }
        appendLine("    shell:")
        appendLine("        \"$shellCmd\"")
        appendLine()
    }

    private fun buildShellCommand(task: TaskDescriptor, nInputs: Int, nOutputs: Int): String {
        val (baseCmd, args) = when (task) {
            is PythonTaskDescriptor -> when (val ep = task.entryPoint) {
                is ScriptEntryPointDescriptor -> "python ${ep.scriptPath}" to task.args
                is ModuleEntryPointDescriptor -> "python -m ${ep.moduleName}" to task.args
            }
            is CommandTaskDescriptor -> task.executable to task.args
            is RTaskDescriptor -> "Rscript ${task.entryPoint.scriptPath}" to task.args
            is InProcessTaskDescriptor -> return "echo 'in-process task: ${task.name}'"
        }

        val substituted = args.map { arg ->
            val inputMatch = Regex("^input\\.(\\d+)$").matchEntire(arg)
            val outputMatch = Regex("^output\\.(\\d+)$").matchEntire(arg)
            when {
                inputMatch != null -> {
                    val idx = inputMatch.groupValues[1].toInt()
                    if (nInputs == 1) "{input}" else "{input[$idx]}"
                }
                outputMatch != null -> {
                    val idx = outputMatch.groupValues[1].toInt()
                    if (nOutputs == 1) "{output}" else "{output[$idx]}"
                }
                else -> arg
            }
        }
        return "$baseCmd ${substituted.joinToString(" ")}"
    }

    private fun portToFilename(id: String, type: String?): String {
        val base = id.replace("-", "_")
        val ext = when (type?.lowercase()) {
            "csv" -> ".csv"
            "png" -> ".png"
            "json" -> ".json"
            "tsv" -> ".tsv"
            else -> ".dat"
        }
        return "$base$ext"
    }
}
