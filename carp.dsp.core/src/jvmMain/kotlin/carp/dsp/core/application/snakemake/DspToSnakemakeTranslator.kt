 package carp.dsp.core.application.snakemake

import carp.dsp.core.application.authoring.descriptor.CommandTaskDescriptor
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

/**
 * Translates a [WorkflowDescriptor] into a Snakemake Snakefile string.
 *
 * Each DSP step becomes one Snakemake rule. Input/output port connections
 * are resolved to concrete filenames (port-id in snake_case + type extension).
 *
 * Arguments using `input.N` / `output.N` placeholders are substituted with
 * Snakemake's `{input}` / `{output}` (single port) or `{input[N]}` / `{output[N]}`
 * (multiple ports) syntax.
 */
object DspToSnakemakeTranslator {

    fun translate(descriptor: WorkflowDescriptor): String = buildString {
        val outputFiles = buildOutputFileMap(descriptor)

        // rule all: target the final step's outputs
        val finalOutputs = descriptor.steps.last().outputs
            .mapNotNull { port -> outputFiles[port.id] }

        appendLine("rule all:")
        appendLine("    input:")
        finalOutputs.forEach { f -> appendLine("        \"$f\",") }
        appendLine()

        descriptor.steps.forEach { step -> appendRule(step, outputFiles) }
    }

    // -- Helpers ------------------------------------------------------------------

    /** Maps every output port id → filename across the whole workflow. */
    private fun buildOutputFileMap(descriptor: WorkflowDescriptor): Map<String, String> =
        buildMap {
            descriptor.steps.forEach { step ->
                step.outputs.forEach { port ->
                    val id = port.id ?: return@forEach
                    put(id, portToFilename(id, port.descriptor?.type))
                }
            }
        }

    private fun StringBuilder.appendRule(step: StepDescriptor, outputFiles: Map<String, String>) {
        val ruleName = (step.id ?: step.task.name)
            .replace(Regex("[^a-zA-Z0-9]+"), "_")
            .trim('_')

        val inputFiles = step.inputs.mapNotNull { port ->
            when (val src = port.source) {
                is StepOutputInputSource -> outputFiles[src.outputId]
                is FileInputSource -> src.path
                else -> null
            }
        }
        val outputFilesList = step.outputs.mapNotNull { port -> outputFiles[port.id] }

        val shellCmd = buildShellCommand(step.task, inputFiles.size, outputFilesList.size)

        appendLine("rule $ruleName:")
        if (inputFiles.isNotEmpty()) {
            appendLine("    input:")
            inputFiles.forEach { f -> appendLine("        \"$f\",") }
        }
        if (outputFilesList.isNotEmpty()) {
            appendLine("    output:")
            outputFilesList.forEach { f -> appendLine("        \"$f\",") }
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

    /** Converts a port id and type string into a concrete filename. */
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
