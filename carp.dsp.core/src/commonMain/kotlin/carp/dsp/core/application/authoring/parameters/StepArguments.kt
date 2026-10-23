package carp.dsp.core.application.authoring.parameters

import carp.dsp.core.application.authoring.descriptor.CommandTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.DefinedStepDescriptor
import carp.dsp.core.application.authoring.descriptor.InProcessTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.PythonTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.RTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.ReferencedStepDescriptor
import carp.dsp.core.application.authoring.descriptor.TaskDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor

private const val FLAG_PREFIX = "--"

private val NAME = Regex("""[A-Za-z_][A-Za-z0-9_.-]*""")

/** A flag's value (the argument) and its index in the step's argument list. */
internal data class ArgumentEntry(val ref: ArgumentRef, val value: String, val index: Int)

/**
 * One step's arguments as defined in the workflow YAML
 * A task's `args`, or a reference's `args` overrides. Library defaults are not included.
 */
internal class StepArguments(val stepKey: String, val args: List<String>)
{
    // Scanned by position rather than into a map: a flag may repeat, as
    // `--param` does, and each occurrence is its own value.
    val entries: List<ArgumentEntry> = args.indices
        .filter { i -> args[i].startsWith(FLAG_PREFIX) && i + 1 < args.size && !args[i + 1].startsWith(FLAG_PREFIX) }
        .map { i ->
            val raw = args[i + 1]
            val name = raw.substringBefore('=', missingDelimiterValue = "")
            if (NAME.matches(name)) ArgumentEntry(ArgumentRef(stepKey, args[i], name), raw.substringAfter('='), i + 1)
            else ArgumentEntry(ArgumentRef(stepKey, args[i]), raw, i + 1)
        }
}

internal fun stepArgumentsOf(workflow: WorkflowDescriptor): List<StepArguments> =
    workflow.steps.mapIndexed { index, step ->
        val key = step.id ?: "#$index"
        when (step)
        {
            is ReferencedStepDescriptor -> StepArguments(key, step.args.orEmpty())
            is DefinedStepDescriptor -> StepArguments(key, argsOf(step.task))
        }
    }

internal fun argsOf(task: TaskDescriptor): List<String> = when (task)
{
    is CommandTaskDescriptor -> task.args
    is PythonTaskDescriptor -> task.args
    is RTaskDescriptor -> task.args
    is InProcessTaskDescriptor -> emptyList()
}

internal fun TaskDescriptor.withArgs(args: List<String>): TaskDescriptor = when (this)
{
    is CommandTaskDescriptor -> copy(args = args)
    is PythonTaskDescriptor -> copy(args = args)
    is RTaskDescriptor -> copy(args = args)
    is InProcessTaskDescriptor -> this
}
