package carp.dsp.core.application.authoring.mapper

import carp.dsp.core.application.authoring.descriptor.*
import dk.cachet.carp.analytics.application.exceptions.ProcessExecutionException
import dk.cachet.carp.analytics.application.exceptions.UnsupportedTaskTypeException
import dk.cachet.carp.analytics.domain.data.DataSchema
import dk.cachet.carp.analytics.domain.data.InputDataSpec
import dk.cachet.carp.analytics.domain.data.OutputDataSpec
import dk.cachet.carp.analytics.domain.environment.EnvironmentDefinition
import dk.cachet.carp.analytics.domain.tasks.*
import dk.cachet.carp.analytics.domain.workflow.*
import dk.cachet.carp.common.application.UUID
import kotlin.collections.emptyList

// -- MetadataExporter ----------------------------------------------------------

/**
 * Maps workflow- and step-level [ComponentMetadata] to their descriptor equivalents.
 */
internal object MetadataExporter
{
    fun exportWorkflowMetadata( meta: WorkflowMetadata ): WorkflowMetadataDescriptor =
        WorkflowMetadataDescriptor(
            id = meta.id.toString(),
            name = meta.name,
            description = meta.description,
            version = meta.version.toString(),
        )

    fun exportStepMetadata( meta: StepMetadata ): StepMetadataDescriptor =
        StepMetadataDescriptor(
            name = meta.name,
            description = meta.description,
            version = meta.version.toString(),
        )
}

// -- PortExporter --------------------------------------------------------------

/**
 * Maps [InputDataSpec] / [OutputDataSpec] to [DataPortDescriptor].
 *
 * **R1 simplification:** only `format` (lowercased enum name → `type`) and
 * `encoding` (→ `format`) are carried over from [DataSchema]. Fields such as
 * `columns`, `jsonSchema`, and `compression` are intentionally dropped and
 * must be re-modelled in a richer [DataDescriptor] if needed in R2.
 */
internal object PortExporter
{
    fun exportInputPort( spec: InputDataSpec ): DataPortDescriptor =
        DataPortDescriptor(
            id = spec.id.toString(),
            descriptor = exportDataSchema( spec.schema ),
        )

    fun exportOutputPort( spec: OutputDataSpec ): DataPortDescriptor =
        DataPortDescriptor(
            id = spec.id.toString(),
            descriptor = exportDataSchema( spec.schema),
        )

    private fun exportDataSchema( schema: DataSchema? ): DataDescriptor? =
        schema?.let {
            DataDescriptor(
                type = it.format.extension,
                format = it.encoding,
            )
        }
}

// -- TaskExporter --------------------------------------------------------------

/**
 * Maps [TaskDefinition] variants and their [dk.cachet.carp.analytics.domain.tasks.ArgToken] lists to descriptor equivalents.
 */
internal object TaskExporter
{
    fun exportTask( task: TaskDefinition ): TaskDescriptor =
        when ( task )
        {
            is CommandTaskDefinition -> exportCommandTask( task )
            is PythonTaskDefinition -> exportPythonTask( task )
            else -> throw UnsupportedTaskTypeException(
                message = "Unsupported task type: '${task::class.simpleName ?: "unknown"}'",
                typeName = task::class.simpleName ?: "unknown"
            )
        }

    fun exportCommandTask( task: CommandTaskDefinition ): CommandTaskDescriptor =
        CommandTaskDescriptor(
            id = task.id.toString(),
            name = task.name,
            description = task.description,
            executable = task.executable,
            args = task.args.map { exportArgTokenToString(it, emptyList(), emptyList()) },
        )

    fun exportPythonTask( task: PythonTaskDefinition ): PythonTaskDescriptor =
        PythonTaskDescriptor(
            id = task.id.toString(),
            name = task.name,
            description = task.description,
            entryPoint = when ( val ep = task.entryPoint )
            {
                is Script -> ScriptEntryPointDescriptor( ep.scriptPath )
                is Module -> ModuleEntryPointDescriptor( ep.moduleName )
            },
            args = task.args.map { exportArgTokenToString(it, emptyList(), emptyList()) },
        )

    /**
     * Converts a domain ArgToken back to a string argument.
     *
     * Reverse of the inference logic in TaskImporter:
     * - Literal(value) → value
     * - InputRef(id) → "input.N" (if id matches inputsN.id)
     * - OutputRef(id) → "output.N" (if id matches outputsN.id)
     * - ParamRef(name) → "param:name"
     *
     * @param token The domain ArgToken
     * @param inputs List of input specs (for reverse lookup)
     * @param outputs List of output specs (for reverse lookup)
     * @return String representation of the argument
     */
    fun exportArgTokenToString(
        token: ArgToken,
        inputs: List<InputDataSpec>,
        outputs: List<OutputDataSpec>
    ): String {
        return when (token) {
            is Literal -> token.value

            is InputRef -> {
                // Try to find the index of this input by ID
                val index = inputs.indexOfFirst { it.id == token.inputId }
                if (index >= 0) {
                    "input.$index"
                } else {
                    // Fallback: use UUID as literal (not ideal but safe)
                    "input.${token.inputId}"
                }
            }

            is OutputRef -> {
                // Try to find the index of this output by ID
                val index = outputs.indexOfFirst { it.id == token.outputId }
                if (index >= 0) {
                    "output.$index"
                } else {
                    // Fallback: use UUID as literal (not ideal but safe)
                    "output.${token.outputId}"
                }
            }

            is ParamRef -> "param:${token.name}"

            // Input path substitution: "--flag=$(input.INDEX)" or "--flag=$(input.UUID)"
            is InputPathSubstitutionRef -> {
                val index = inputs.indexOfFirst { it.id == token.inputId }
                val refPattern = if (index >= 0) "input.$index" else "input.${token.inputId}"
                token.template.replace("$()", "$(input.$refPattern)")
            }

            // Output path substitution: "--flag=$(output.INDEX)" or "--flag=$(output.UUID)"
            is OutputPathSubstitutionRef -> {
                val index = outputs.indexOfFirst { it.id == token.outputId }
                val refPattern = if (index >= 0) "output.$index" else "output.${token.outputId}"
                token.template.replace("$()", "P$(output.$refPattern)")
            }
        }
    }
}

// -- EnvironmentExporter -------------------------------------------------------

/**
 * Maps [EnvironmentDefinition] implementations to [EnvironmentDescriptor].
 *
 * **Coupling note:** detection relies on `KClass.simpleName`.
 * Renaming a concrete environment class will silently fall through to kind `"unknown"`.
 *
 * TODO(arch): replace simpleName dispatch with a type-safe extension point once
 *   environment implementations are owned entirely by carp-dsp.
 */
internal object EnvironmentExporter
{
    fun exportEnvironments(
        environments: Map<UUID, EnvironmentDefinition>
    ): Map<String, EnvironmentDescriptor> =
        environments.entries
            .sortedBy { it.key.toString() }
            .associate { ( uuid, env ) -> uuid.toString() to exportEnvironment( env ) }

    fun exportEnvironment( env: EnvironmentDefinition ): EnvironmentDescriptor
    {
        val ( kind, spec ) = resolveKindAndSpec( env )
        return EnvironmentDescriptor( name = env.name, kind = kind, spec = spec )
    }

    private fun resolveKindAndSpec( env: EnvironmentDefinition ): Pair<String, Map<String, List<String>>>
    {
        val baseSpec: MutableMap<String, List<String>> = mutableMapOf(
            "dependencies" to env.dependencies,
        )
        env.environmentVariables.forEach { ( k, v ) -> baseSpec["env.$k"] = listOf( v ) }

        return when ( env::class.simpleName )
        {
            "CondaEnvironmentDefinition" ->
            {
                ( env as? carp.dsp.core.application.environment.CondaEnvironmentDefinition )?.let {
                    baseSpec["pythonVersion"] = listOf( it.pythonVersion )
                    baseSpec["channels"] = it.channels
                }
                "conda" to baseSpec
            }
            "PixiEnvironmentDefinition" ->
            {
                ( env as? carp.dsp.core.application.environment.PixiEnvironmentDefinition )?.let {
                    baseSpec["pythonVersion"] = listOf( it.pythonVersion )
                    baseSpec["channels"] = it.channels
                }
                "pixi" to baseSpec
            }
            "SystemEnvironmentDefinition" -> "system" to baseSpec
            "DockerEnvironmentDefinition" ->
            {
                ( env as? carp.dsp.core.application.environment.DockerEnvironmentDefinition )?.let {
                    baseSpec["image"] = listOf( it.image )
                }
                "docker" to baseSpec
            }
            else -> "unknown" to baseSpec
        }
    }
}

// -- WorkflowDescriptorExporter ------------------------------------------------

/**
 * Converts canonical domain models to [WorkflowDescriptor] DTOs.
 *
 * This is the **strict-write** boundary: all output IDs are concrete (never null),
 * ordering is deterministic (insertion order for steps; sorted-by-key for environments),
 * and no domain types leak into the produced descriptors.
 *
 * Supported [TaskDefinition] types:
 * - [CommandTaskDefinition] → [CommandTaskDescriptor]
 * - [PythonTaskDefinition]  → [PythonTaskDescriptor]
 * - Any unknown type        → throws [ProcessExecutionException]
 *
 * Delegates to focused sub-exporters:
 * - [MetadataExporter] — workflow / step metadata
 * - [PortExporter]     — input / output data ports
 * - [TaskExporter]     — task definitions and arg tokens
 * - [EnvironmentExporter] — environment definitions
 */
class WorkflowDescriptorExporter
{
    // -- Public entry point ----------------------------------------------------

    fun export( definition: WorkflowDefinition, schemaVersion: String = "1.0" ): WorkflowDescriptor =
        WorkflowDescriptor(
            schemaVersion = schemaVersion,
            metadata = MetadataExporter.exportWorkflowMetadata( definition.workflow.metadata ),
            steps = exportSteps( definition.workflow ),
            environments = EnvironmentExporter.exportEnvironments( definition.environments ),
        )

    // -- Steps (only method that combines sub-exporters) -----------------------

    internal fun exportSteps( workflow: Workflow ): List<StepDescriptor> =
        workflow.getComponents()
            .filterIsInstance<Step>()
            .map { exportStep( it ) }

    internal fun exportStep( step: Step ): StepDescriptor =
        StepDescriptor(
            id = step.metadata.id.toString(),
            metadata = MetadataExporter.exportStepMetadata( step.metadata ),
            environmentId = step.environmentId.toString(),
            // TODO(R2): populate dependsOn once Step models explicit upstream edges.
            dependsOn = emptyList(),
            task = TaskExporter.exportTask( step.task ),
            inputs = step.inputs.map { PortExporter.exportInputPort( it ) },
            outputs = step.outputs.map { PortExporter.exportOutputPort( it ) },
        )

    internal fun exportCommandTask( task: CommandTaskDefinition ): CommandTaskDescriptor =
        TaskExporter.exportCommandTask( task )

    internal fun exportTask( task: TaskDefinition ): TaskDescriptor =
        TaskExporter.exportTask( task )

    internal fun exportEnvironment( env: EnvironmentDefinition ): EnvironmentDescriptor =
        EnvironmentExporter.exportEnvironment( env )
}
