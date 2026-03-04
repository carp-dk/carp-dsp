package carp.dsp.core.application.authoring.mapper

import carp.dsp.core.application.authoring.descriptor.*
import dk.cachet.carp.analytics.domain.data.DataSchema
import dk.cachet.carp.analytics.domain.data.InputDataSpec
import dk.cachet.carp.analytics.domain.data.OutputDataSpec
import dk.cachet.carp.analytics.domain.environment.EnvironmentDefinition
import dk.cachet.carp.analytics.domain.tasks.*
import dk.cachet.carp.analytics.domain.workflow.*
import dk.cachet.carp.common.application.UUID

// ── MetadataExporter ──────────────────────────────────────────────────────────

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

// ── PortExporter ──────────────────────────────────────────────────────────────

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
            descriptor = exportDataSchema( spec.schema ),
        )

    private fun exportDataSchema( schema: DataSchema? ): DataDescriptor? =
        schema?.let {
            DataDescriptor(
                type = it.format.name.lowercase(),
                format = it.encoding,
            )
        }
}

// ── TaskExporter ──────────────────────────────────────────────────────────────

/**
 * Maps [TaskDefinition] variants and their [ArgToken] lists to descriptor equivalents.
 */
internal object TaskExporter
{
    fun exportTask( task: TaskDefinition ): TaskDescriptor =
        when ( task )
        {
            is CommandTaskDefinition -> exportCommandTask( task )
            is PythonTaskDefinition -> exportPythonTask( task )
            else -> throw UnsupportedTaskTypeException( task::class.simpleName ?: "unknown" )
        }

    fun exportCommandTask( task: CommandTaskDefinition ): CommandTaskDescriptor =
        CommandTaskDescriptor(
            id = task.id.toString(),
            name = task.name,
            description = task.description,
            executable = task.executable,
            args = task.args.map { exportArgToken( it ) },
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
            args = task.args.map { exportArgToken( it ) },
        )

    fun exportArgToken( token: ArgToken ) =
        when ( token )
        {
            is Literal -> LiteralArgDescriptor( token.value )
            is InputRef -> InputRefArgDescriptor( token.inputId.toString() )
            is OutputRef -> OutputRefArgDescriptor( token.outputId.toString() )
            is ParamRef -> ParamRefArgDescriptor( token.name )
        }
}

// ── EnvironmentExporter ───────────────────────────────────────────────────────

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

    private fun resolveKindAndSpec( env: EnvironmentDefinition ): Pair<String, Map<String, String>>
    {
        val baseSpec: MutableMap<String, String> = mutableMapOf(
            "dependencies" to env.dependencies.joinToString(","),
        )
        env.environmentVariables.forEach { ( k, v ) -> baseSpec["env.$k"] = v }

        return when ( env::class.simpleName )
        {
            "CondaEnvironmentDefinition" ->
            {
                ( env as? carp.dsp.core.application.environment.CondaEnvironmentDefinition )?.let {
                    baseSpec["pythonVersion"] = it.pythonVersion
                    baseSpec["channels"] = it.channels.joinToString(",")
                }
                "conda" to baseSpec
            }
            "PixiEnvironmentDefinition" ->
            {
                ( env as? carp.dsp.core.application.environment.PixiEnvironmentDefinition )?.let {
                    baseSpec["pythonVersion"] = it.pythonVersion
                    baseSpec["channels"] = it.channels.joinToString(",")
                }
                "pixi" to baseSpec
            }
            else -> "unknown" to baseSpec
        }
    }
}

// ── WorkflowDescriptorExporter ────────────────────────────────────────────────

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
 * - Any unknown type        → throws [UnsupportedTaskTypeException]
 *
 * Delegates to focused sub-exporters:
 * - [MetadataExporter] — workflow / step metadata
 * - [PortExporter]     — input / output data ports
 * - [TaskExporter]     — task definitions and arg tokens
 * - [EnvironmentExporter] — environment definitions
 */
class WorkflowDescriptorExporter
{
    // ── Public entry point ────────────────────────────────────────────────────

    fun export( definition: WorkflowDefinition, schemaVersion: String = "1.0" ): WorkflowDescriptor =
        WorkflowDescriptor(
            schemaVersion = schemaVersion,
            metadata = MetadataExporter.exportWorkflowMetadata( definition.workflow.metadata ),
            steps = exportSteps( definition.workflow ),
            environments = EnvironmentExporter.exportEnvironments( definition.environments ),
        )

    // ── Steps (only method that combines sub-exporters) ───────────────────────

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

    // ── Delegating shims (keep test call-sites unchanged) ─────────────────────

    internal fun exportCommandTask( task: CommandTaskDefinition ) =
        TaskExporter.exportCommandTask( task )

    internal fun exportArgToken( token: ArgToken ) =
        TaskExporter.exportArgToken( token )

    internal fun exportTask( task: TaskDefinition ) =
        TaskExporter.exportTask( task )

    internal fun exportEnvironment( env: EnvironmentDefinition ) =
        EnvironmentExporter.exportEnvironment( env )
}

/**
 * Thrown when the exporter encounters a [TaskDefinition] subtype it does not know how to map.
 */
class UnsupportedTaskTypeException( typeName: String ) :
    IllegalArgumentException(
        "Cannot export TaskDefinition of type '$typeName'. " +
            "Register a handler in TaskExporter.exportTask()."
    )
