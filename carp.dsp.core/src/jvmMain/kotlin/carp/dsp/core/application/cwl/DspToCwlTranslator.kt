package carp.dsp.core.application.cwl

import carp.dsp.core.application.authoring.descriptor.CommandTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.DataPortDescriptor
import carp.dsp.core.application.authoring.descriptor.EnvironmentDescriptor
import carp.dsp.core.application.authoring.descriptor.FileInputSource
import carp.dsp.core.application.authoring.descriptor.FileOutputDestination
import carp.dsp.core.application.authoring.descriptor.InProcessTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.ModuleEntryPointDescriptor
import carp.dsp.core.application.authoring.descriptor.PythonTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.RTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.ScriptEntryPointDescriptor
import carp.dsp.core.application.authoring.descriptor.StepDescriptor
import carp.dsp.core.application.authoring.descriptor.TaskDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor

/**
 * Translates a [WorkflowDescriptor] into CWL [CwlStepAsset] documents.
 *
 * R1 scope: one CWL `CommandLineTool` per DSP step.
 * [InProcessTaskDescriptor] steps are skipped — they have no CWL equivalent.
 * Docker environments are not yet supported — see ticket E1.
 * Multi-step `Workflow` class, Scatter, and sub-workflows are out of scope for R1.
 */
object DspToCwlTranslator {

    fun translate(descriptor: WorkflowDescriptor): List<CwlStepAsset> =
        descriptor.steps.mapNotNull { step ->
            translateStep(step, descriptor.environments)
        }

    private fun translateStep(
        step: StepDescriptor,
        environments: Map<String, EnvironmentDescriptor>,
    ): CwlStepAsset? {
        val (baseCommand, arguments) = commandFor(step.task) ?: return null
        val env = environments[step.environmentId]

        val softwarePackages = if (env?.kind in listOf("conda", "pixi"))
            env?.spec?.get("dependencies") ?: emptyList()
        else emptyList()

        // Environment variables declared as KEY=VALUE entries under spec["env"]
        val envVars = env?.spec?.get("env")
            ?.mapNotNull { entry ->
                val parts = entry.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            } ?: emptyList()

        val stepId = step.id ?: step.task.name
        return CwlStepAsset(
            stepId = stepId,
            content = buildCwlYaml(baseCommand, arguments, softwarePackages, envVars, step.inputs, step.outputs),
        )
    }

    // -- Task → CWL baseCommand ------------------------------------------------

    /**
     * Returns (baseCommand, arguments) for supported task types, or null for
     * [InProcessTaskDescriptor] which cannot be represented in CWL.
     */
    private fun commandFor(task: TaskDescriptor): Pair<List<String>, List<String>>? = when (task) {
        is CommandTaskDescriptor -> listOf(task.executable) to task.args
        is PythonTaskDescriptor -> when (val ep = task.entryPoint) {
            is ScriptEntryPointDescriptor -> listOf("python", ep.scriptPath) to task.args
            is ModuleEntryPointDescriptor -> listOf("python", "-m", ep.moduleName) to task.args
        }
        is RTaskDescriptor -> listOf("Rscript", task.entryPoint.scriptPath) to task.args
        is InProcessTaskDescriptor -> null
    }

    // -- CWL YAML builder ------------------------------------------------------

    private fun buildCwlYaml(
        baseCommand: List<String>,
        arguments: List<String>,
        softwarePackages: List<String>,
        envVars: List<Pair<String, String>>,
        inputs: List<DataPortDescriptor>,
        outputs: List<DataPortDescriptor>,
    ): String = buildString {
        appendLine("cwlVersion: v1.2")
        appendLine("class: CommandLineTool")

        appendLine("baseCommand:")
        baseCommand.forEach { appendLine("  - ${q(it)}") }

        if (arguments.isNotEmpty()) {
            appendLine("arguments:")
            arguments.forEach { appendLine("  - ${q(it)}") }
        }

        if (softwarePackages.isNotEmpty() || envVars.isNotEmpty()) {
            appendLine("requirements:")
            if (softwarePackages.isNotEmpty()) {
                appendLine("  SoftwareRequirement:")
                appendLine("    packages:")
                softwarePackages.forEach { pkg -> appendLine("      - package: ${q(pkg)}") }
            }
            if (envVars.isNotEmpty()) {
                appendLine("  EnvVarRequirement:")
                appendLine("    envDef:")
                envVars.forEach { (k, v) ->
                    appendLine("      - envName: ${q(k)}")
                    appendLine("        envValue: ${q(v)}")
                }
            }
        }

        appendLine("inputs:")
        if (inputs.isEmpty()) {
            appendLine("  {}")
        } else {
            inputs.forEachIndexed { i, port ->
                val id = (port.id ?: "input_$i").cwlId()
                val type = if (port.source is FileInputSource) "File" else "string"
                appendLine("  $id:")
                appendLine("    type: $type")
                appendLine("    inputBinding:")
                appendLine("      position: ${i + 1}")
            }
        }

        appendLine("outputs:")
        if (outputs.isEmpty()) {
            appendLine("  {}")
        } else {
            outputs.forEachIndexed { i, port ->
                val id = (port.id ?: "output_$i").cwlId()
                val glob = (port.destination as? FileOutputDestination)?.path ?: "output_$i"
                appendLine("  $id:")
                appendLine("    type: File")
                appendLine("    outputBinding:")
                appendLine("      glob: ${q(glob)}")
            }
        }
    }.trimEnd()

    /** Quote a YAML scalar if it contains characters that require quoting. */
    private fun q(s: String): String =
        if (needsQuoting(s)) "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\"" else s

    private fun needsQuoting(s: String): Boolean =
        s.isEmpty() || s.any { it in ":{}[]#&*?|>!'\"@`" } || s.first().isWhitespace() || s.last().isWhitespace()

    /** Sanitize a port ID to a valid CWL identifier (replace hyphens with underscores). */
    private fun String.cwlId(): String = replace("-", "_")
}
