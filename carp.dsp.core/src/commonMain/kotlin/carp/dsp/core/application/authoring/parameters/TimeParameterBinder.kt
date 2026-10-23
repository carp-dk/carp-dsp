package carp.dsp.core.application.authoring.parameters

import carp.dsp.core.application.authoring.descriptor.DefinedStepDescriptor
import carp.dsp.core.application.authoring.descriptor.ReferencedStepDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor

/**
 * Returns [workflow] with each argument in [values] set to its new value.
 *
 * Only values already present are replaced; nothing is added, so a workflow
 * never gains an argument it did not state. For a `--flag name=value`
 * argument, only the part after `=` changes.
 *
 * @throws IllegalArgumentException when a key in [values] names an argument the
 *   workflow does not have, so a stale binding fails instead of doing nothing.
 */
fun bindArguments(workflow: WorkflowDescriptor, values: Map<ArgumentRef, String>): WorkflowDescriptor
{
    if (values.isEmpty()) return workflow

    val arguments = stepArgumentsOf(workflow)
    val present = arguments.flatMap { step -> step.entries.map { it.ref } }.toSet()
    val unknown = values.keys - present
    require(unknown.isEmpty())
    {
        "The workflow has no argument " + unknown.joinToString { "'${it.label}' on step '${it.stepKey}'" } + "."
    }

    val steps = workflow.steps.mapIndexed { index, step ->
        val stepValues = values.filterKeys { it.stepKey == arguments[index].stepKey }
        if (stepValues.isEmpty()) return@mapIndexed step

        val args = arguments[index].replaced(stepValues)
        when (step)
        {
            is ReferencedStepDescriptor -> step.copy(args = args)
            is DefinedStepDescriptor -> step.copy(task = step.task.withArgs(args))
        }
    }

    return workflow.copy(steps = steps)
}

private fun StepArguments.replaced(values: Map<ArgumentRef, String>): List<String>
{
    val result = args.toMutableList()
    entries.forEach { entry ->
        val value = values[entry.ref] ?: return@forEach
        result[entry.index] = if (entry.ref.name == null) value else "${entry.ref.name}=$value"
    }

    return result
}
