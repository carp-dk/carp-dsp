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
        val ctx = CwlYamlContext(baseCommand, arguments, softwarePackages, envVars, step.inputs, step.outputs)
        return CwlStepAsset(
            stepId = stepId,
            content = buildCwlYaml(ctx),
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

    private data class CwlYamlContext(
        val baseCommand: List<String>,
        val arguments: List<String>,
        val softwarePackages: List<String>,
        val envVars: List<Pair<String, String>>,
        val inputs: List<DataPortDescriptor>,
        val outputs: List<DataPortDescriptor>,
    )

    private fun buildCwlYaml(ctx: CwlYamlContext): String {
        return buildString {
            appendLine("cwlVersion: v1.2")
            appendLine("class: CommandLineTool")
            appendBaseCommand(ctx)
            appendArguments(ctx)
            appendRequirements(ctx)
            appendInputs(ctx)
            appendOutputs(ctx)
        }.trimEnd()
    }

    private fun StringBuilder.appendBaseCommand(ctx: CwlYamlContext) {
        appendLine("baseCommand:")
        ctx.baseCommand.forEach { appendLine("  - ${q(it)}") }
    }

    private fun StringBuilder.appendArguments(ctx: CwlYamlContext) {
        if (ctx.arguments.isNotEmpty()) {
            appendLine("arguments:")
            ctx.arguments.forEach { appendLine("  - ${q(it)}") }
        }
    }

    private fun StringBuilder.appendRequirements(ctx: CwlYamlContext) {
        // SoftwareRequirement goes in hints (advisory; cwltool rejects it in requirements)
        if (ctx.softwarePackages.isNotEmpty()) {
            appendLine("hints:")
            appendLine("  SoftwareRequirement:")
            appendLine("    packages:")
            ctx.softwarePackages.forEach { pkg -> appendLine("      - package: ${q(pkg)}") }
        }
        // EnvVarRequirement stays in requirements (fully supported)
        if (ctx.envVars.isNotEmpty()) {
            appendLine("requirements:")
            appendLine("  EnvVarRequirement:")
            appendLine("    envDef:")
            ctx.envVars.forEach { (k, v) ->
                appendLine("      - envName: ${q(k)}")
                appendLine("        envValue: ${q(v)}")
            }
        }
    }

    private fun StringBuilder.appendInputs(ctx: CwlYamlContext) {
        appendLine("inputs:")
        if (ctx.inputs.isEmpty()) {
            appendLine("  {}")
        } else {
            ctx.inputs.forEachIndexed { i, port ->
                val id = (port.id ?: "input_$i").cwlId()
                val type = if (port.source is FileInputSource) "File" else "string"
                appendLine("  $id:")
                appendLine("    type: $type")
                appendLine("    inputBinding:")
                appendLine("      position: ${i + 1}")
            }
        }
    }

    private fun StringBuilder.appendOutputs(ctx: CwlYamlContext) {
        appendLine("outputs:")
        if (ctx.outputs.isEmpty()) {
            appendLine("  {}")
        } else {
            ctx.outputs.forEachIndexed { i, port ->
                val id = (port.id ?: "output_$i").cwlId()
                val glob = (port.destination as? FileOutputDestination)?.path ?: "output_$i"
                appendLine("  $id:")
                appendLine("    type: File")
                appendLine("    outputBinding:")
                appendLine("      glob: ${q(glob)}")
            }
        }
    }

    /** Quote a YAML scalar if it contains characters that require quoting. */
    private fun q(s: String): String =
        if (needsQuoting(s)) "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\"" else s

    private fun needsQuoting(s: String): Boolean =
        s.isEmpty() || s.any { it in ":{}[]#&*?|>!'\"@`" } || s.first().isWhitespace() || s.last().isWhitespace()

    /** Sanitize a port ID to a valid CWL identifier (replace hyphens with underscores). */
    private fun String.cwlId(): String = replace("-", "_")
}
