package carp.dsp.core.application.authoring.mapper

import carp.dsp.core.application.authoring.descriptor.ArgTokenDescriptor
import carp.dsp.core.application.authoring.descriptor.CommandTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.InProcessTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.InputRefArgDescriptor
import carp.dsp.core.application.authoring.descriptor.LiteralArgDescriptor
import carp.dsp.core.application.authoring.descriptor.ModuleEntryPointDescriptor
import carp.dsp.core.application.authoring.descriptor.OutputRefArgDescriptor
import carp.dsp.core.application.authoring.descriptor.ParamRefArgDescriptor
import carp.dsp.core.application.authoring.descriptor.PythonTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.ScriptEntryPointDescriptor
import carp.dsp.core.application.authoring.descriptor.TaskDescriptor
import dk.cachet.carp.analytics.domain.tasks.ArgToken
import dk.cachet.carp.analytics.domain.tasks.CommandTaskDefinition
import dk.cachet.carp.analytics.domain.tasks.InputRef
import dk.cachet.carp.analytics.domain.tasks.Literal
import dk.cachet.carp.analytics.domain.tasks.Module
import dk.cachet.carp.analytics.domain.tasks.OutputRef
import dk.cachet.carp.analytics.domain.tasks.ParamRef
import dk.cachet.carp.analytics.domain.tasks.PythonTaskDefinition
import dk.cachet.carp.analytics.domain.tasks.Script
import dk.cachet.carp.analytics.domain.tasks.TaskDefinition
import dk.cachet.carp.common.application.UUID

/**
 * Maps [TaskDescriptor] variants to [TaskDefinition] domain equivalents.
 *
 * Handles three task types:
 * - [CommandTaskDescriptor] → [CommandTaskDefinition]
 * - [PythonTaskDescriptor] → [PythonTaskDefinition]
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
        workflowNamespace: UUID
    ): TaskDefinition =
        when ( descriptor )
        {
            is CommandTaskDescriptor -> importCommandTask( descriptor, workflowNamespace )
            is PythonTaskDescriptor -> importPythonTask( descriptor, workflowNamespace )
            is InProcessTaskDescriptor -> throw UnsupportedTaskTypeException( "in-process" )
        }

    /**
     * Imports a command task descriptor.
     *
     * @param descriptor The command task descriptor
     * @param workflowNamespace The namespace UUID for deterministic ID generation
     * @return Domain CommandTaskDefinition
     */
    fun importCommandTask(
        descriptor: CommandTaskDescriptor,
        workflowNamespace: UUID
    ): CommandTaskDefinition =
        CommandTaskDefinition(
            id = descriptor.id?.let { tryParseUuid( it ) }
                ?: DeterministicUUID.v5(workflowNamespace, "task:cmd:${descriptor.name}"),
            name = descriptor.name,
            description = descriptor.description,
            executable = descriptor.executable,
            args = descriptor.args.map { importArgToken( it, workflowNamespace ) },
        )

    /**
     * Imports a Python task descriptor.
     *
     * @param pythonTaskDescriptor The Python task descriptor
     * @param workflowNamespace The namespace UUID for deterministic ID generation
     * @return Domain PythonTaskDefinition
     */
    fun importPythonTask(
        pythonTaskDescriptor: PythonTaskDescriptor,
        workflowNamespace: UUID
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
            args = pythonTaskDescriptor.args.map { importArgToken( it, workflowNamespace ) },
        )

    /**
     * Maps an argument token descriptor to a domain arg token.
     *
     * @param argTokenDescriptor The argument token descriptor
     * @param workflowNamespace The namespace UUID for deterministic ID generation
     * @return Domain ArgToken
     */
    fun importArgToken( argTokenDescriptor: ArgTokenDescriptor, workflowNamespace: UUID ): ArgToken =
        when ( argTokenDescriptor )
        {
            is LiteralArgDescriptor -> Literal( argTokenDescriptor.value )
            is InputRefArgDescriptor -> InputRef(
                tryParseUuid( argTokenDescriptor.inputId )
                    ?: DeterministicUUID.v5( workflowNamespace, "port:input:${argTokenDescriptor.inputId}" )
            )
            is OutputRefArgDescriptor -> OutputRef(
                tryParseUuid( argTokenDescriptor.outputId )
                    ?: DeterministicUUID.v5( workflowNamespace, "port:output:${argTokenDescriptor.outputId}" )
            )
            is ParamRefArgDescriptor -> ParamRef( argTokenDescriptor.name )
        }
}

