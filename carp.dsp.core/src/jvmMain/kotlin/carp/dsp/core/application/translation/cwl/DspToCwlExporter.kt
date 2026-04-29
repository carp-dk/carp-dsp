package carp.dsp.core.application.translation.cwl

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
import carp.dsp.core.application.translation.WorkflowExporter

/**
 * Exports a [WorkflowDescriptor] to a list of CWL v1.2 [CwlDocument]s — one per step.
 *
 * [InProcessTaskDescriptor] steps are skipped (no CWL equivalent).
 * Docker environments are not yet supported
 */
object DspToCwlExporter : WorkflowExporter<List<CwlDocument>> {

    override fun export(descriptor: WorkflowDescriptor): List<CwlDocument> =
        descriptor.steps.mapNotNull { step ->
            exportStep(step, descriptor.environments)
        }

    private fun exportStep(
        step: StepDescriptor,
        environments: Map<String, EnvironmentDescriptor>,
    ): CwlDocument? {
        val (baseCommand, arguments) = commandFor(step.task) ?: return null
        val env = environments[step.environmentId]

        val softwarePackages = if (env?.kind in listOf("conda", "pixi"))
            env?.spec?.get("dependencies") ?: emptyList()
        else emptyList()

        val envVars = env?.spec?.get("env")
            ?.mapNotNull { entry ->
                val parts = entry.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            } ?: emptyList()

        val stepId = step.id ?: step.task.name
        val ctx = CwlYamlContext(baseCommand, arguments, softwarePackages, envVars, step.inputs, step.outputs)
        return CwlDocument(stepId = stepId, content = buildCwlYaml(ctx))
    }

    // -- Task → CWL baseCommand -----------------------------------------------

    private fun commandFor(task: TaskDescriptor): Pair<List<String>, List<String>>? = when (task) {
        is CommandTaskDescriptor -> listOf(task.executable) to task.args
        is PythonTaskDescriptor -> when (val ep = task.entryPoint) {
            is ScriptEntryPointDescriptor -> listOf("python", ep.scriptPath) to task.args
            is ModuleEntryPointDescriptor -> listOf("python", "-m", ep.moduleName) to task.args
        }
        is RTaskDescriptor -> listOf("Rscript", task.entryPoint.scriptPath) to task.args
        is InProcessTaskDescriptor -> null
    }

    // -- CWL YAML builder -----------------------------------------------------

    private data class CwlYamlContext(
        val baseCommand: List<String>,
        val arguments: List<String>,
        val softwarePackages: List<String>,
        val envVars: List<Pair<String, String>>,
        val inputs: List<DataPortDescriptor>,
        val outputs: List<DataPortDescriptor>,
    )

    private fun buildCwlYaml(ctx: CwlYamlContext): String = buildString {
        appendLine("cwlVersion: v1.2")
        appendLine("class: CommandLineTool")
        appendBaseCommand(ctx)
        appendArguments(ctx)
        appendRequirements(ctx)
        appendInputs(ctx)
        appendOutputs(ctx)
    }.trimEnd()

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
        if (ctx.softwarePackages.isNotEmpty()) {
            appendLine("hints:")
            appendLine("  SoftwareRequirement:")
            appendLine("    packages:")
            ctx.softwarePackages.forEach { pkg -> appendLine("      - package: ${q(pkg)}") }
        }
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

    private fun q(s: String): String =
        if (needsQuoting(s)) "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\"" else s

    private fun needsQuoting(s: String): Boolean =
        s.isEmpty() || s.any { it in ":{}[]#&*?|>!'\"@`" } || s.first().isWhitespace() || s.last().isWhitespace()

    private fun String.cwlId(): String = replace("-", "_")
}
