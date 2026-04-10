package carp.dsp.core.application.authoring.mapper

import carp.dsp.core.application.authoring.descriptor.*
import dk.cachet.carp.analytics.application.exceptions.UnsupportedTaskTypeException
import dk.cachet.carp.analytics.domain.data.InputDataSpec
import dk.cachet.carp.analytics.domain.data.OutputDataSpec
import dk.cachet.carp.analytics.domain.tasks.*
import dk.cachet.carp.common.application.UUID

/**
 * Maps [TaskDescriptor] variants to [TaskDefinition] domain equivalents.
 *
 * Handles four task types:
 * - [CommandTaskDescriptor] → [CommandTaskDefinition]
 * - [PythonTaskDescriptor] → [PythonTaskDefinition]
 * - [RTaskDescriptor] → [RTaskDefinition]
 * - [InProcessTaskDescriptor] → Throws (not yet supported)
 *
 */
internal object TaskImporter
{
    /**
     * Imports a task descriptor to a domain task definition.
     *
     * @param descriptor The task descriptor
     * @param workflowNamespace The namespace UUID for deterministic ID generation
     * @return Domain TaskDefinition
     * @throws UnsupportedTaskTypeException if task type is in-process
     */
    fun importTask(
        descriptor: TaskDescriptor,
        workflowNamespace: UUID,
        inputs: List<InputDataSpec> = emptyList(),
        outputs: List<OutputDataSpec> = emptyList()
    ): TaskDefinition =
        when ( descriptor )
        {
            is CommandTaskDescriptor -> importCommandTask( descriptor, workflowNamespace, inputs, outputs )
            is PythonTaskDescriptor -> importPythonTask( descriptor, workflowNamespace, inputs, outputs )
            is RTaskDescriptor -> importRTask( descriptor, workflowNamespace, inputs, outputs )
            is InProcessTaskDescriptor -> throw UnsupportedTaskTypeException(
                "in-process task descriptor is not supported",
                InProcessTaskDescriptor::class.qualifiedName!!
            )
        }

    /**
     * Imports a command task descriptor.
     *
     * @param descriptor The command task descriptor
     * @param workflowNamespace The namespace UUID for deterministic ID generation
     * @param inputs List of input ports (for argument inference)
     * @param outputs List of output ports (for argument inference)
     * @return Domain CommandTaskDefinition
     */
    fun importCommandTask(
        descriptor: CommandTaskDescriptor,
        workflowNamespace: UUID,
        inputs: List<InputDataSpec> = emptyList(),
        outputs: List<OutputDataSpec> = emptyList()
    ): CommandTaskDefinition =
        CommandTaskDefinition(
            id = descriptor.id?.let { tryParseUuid( it ) }
                ?: DeterministicUUID.v5(workflowNamespace, "task:cmd:${descriptor.name}"),
            name = descriptor.name,
            description = descriptor.description,
            executable = descriptor.executable,
            args = descriptor.args.map { inferAndImportArgument(it, inputs, outputs, workflowNamespace) },
        )

    /**
     * Imports a Python task descriptor.
     *
     * @param pythonTaskDescriptor The Python task descriptor
     * @param workflowNamespace The namespace UUID for deterministic ID generation
     * @param inputs List of input ports (for argument inference)
     * @param outputs List of output ports (for argument inference)
     * @return Domain PythonTaskDefinition
     */
    fun importPythonTask(
        pythonTaskDescriptor: PythonTaskDescriptor,
        workflowNamespace: UUID,
        inputs: List<InputDataSpec> = emptyList(),
        outputs: List<OutputDataSpec> = emptyList()
    ): PythonTaskDefinition =
        PythonTaskDefinition(
            id = pythonTaskDescriptor.id?.let { tryParseUuid( it ) }
                ?: DeterministicUUID.v5(workflowNamespace, "task:py:${pythonTaskDescriptor.name}"),
            name = pythonTaskDescriptor.name,
            description = pythonTaskDescriptor.description,
            entryPoint = when ( val ep = pythonTaskDescriptor.entryPoint )
            {
                is ScriptEntryPointDescriptor -> Script( ep.scriptPath )
                is ModuleEntryPointDescriptor -> Module( ep.moduleName )
            },
            args = pythonTaskDescriptor.args.map { inferAndImportArgument(it, inputs, outputs, workflowNamespace) },
        )

    /**
     * Imports an R task descriptor.
     *
     * @param rTaskDescriptor The R task descriptor
     * @param workflowNamespace The namespace UUID for deterministic ID generation
     * @param inputs List of input ports (for argument inference)
     * @param outputs List of output ports (for argument inference)
     * @return Domain RTaskDefinition
     */
    fun importRTask(
        rTaskDescriptor: RTaskDescriptor,
        workflowNamespace: UUID,
        inputs: List<InputDataSpec> = emptyList(),
        outputs: List<OutputDataSpec> = emptyList()
    ): RTaskDefinition =
        RTaskDefinition(
            id = rTaskDescriptor.id?.let { tryParseUuid( it ) }
                ?: DeterministicUUID.v5(workflowNamespace, "task:r:${rTaskDescriptor.name}"),
            name = rTaskDescriptor.name,
            description = rTaskDescriptor.description,
            entryPoint = when ( val ep = rTaskDescriptor.entryPoint )
            {
                else -> RScript(ep.scriptPath)
            },
            args = rTaskDescriptor.args.map { inferAndImportArgument(it, inputs, outputs, workflowNamespace) },
        )

    /**
     * Infers the type of argument string and converts it to a domain ArgToken.
     *
     * Type inference rules:
     * - `input.N` → InputRef(inputsN.id)
     * - `output.N` → OutputRef(outputsN.id)
     * - `param:NAME` → ParamRef(NAME)
     * - Anything else → Literal
     *
     * @param argument The argument string
     * @param inputs List of input ports
     * @param outputs List of output ports
     * @param workflowNamespace Namespace for UUID generation (if needed)
     * @return Domain ArgToken
     */
    private fun inferAndImportArgument(
        argument: String,
        inputs: List<InputDataSpec>,
        outputs: List<OutputDataSpec>,
        workflowNamespace: UUID
    ): ArgToken {
        return when {
            // Rule 1: input.N → InputRef
            argument.matches(Regex("^input\\.(\\d+)$")) -> {
                val index = argument.substringAfter(".").toInt()
                if (index >= inputs.size) {
                    // Fallback: generate deterministic UUID
                    InputRef(
                        DeterministicUUID.v5(workflowNamespace, "port:input:$argument")
                    )
                } else {
                    InputRef(inputs[index].id)
                }
            }

            // Rule 2: output.N → OutputRef
            argument.matches(Regex("^output\\.(\\d+)$")) -> {
                val index = argument.substringAfter(".").toInt()
                if (index >= outputs.size) {
                    // Fallback: generate deterministic UUID
                    OutputRef(
                        DeterministicUUID.v5(workflowNamespace, "port:output:$argument")
                    )
                } else {
                    OutputRef(outputs[index].id)
                }
            }

            // Rule 3: param:NAME → ParamRef
            argument.matches(Regex("^param:([a-zA-Z0-9_]+)$")) -> {
                val name = argument.substringAfter("param:")
                ParamRef(name)
            }

            // Rule 4 & 5: Everything else → Literal
            else -> Literal(argument)
        }
    }
}

