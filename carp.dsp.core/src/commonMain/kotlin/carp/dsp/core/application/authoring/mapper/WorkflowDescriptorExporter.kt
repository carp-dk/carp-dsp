package carp.dsp.core.application.authoring.mapper

import carp.dsp.core.application.authoring.descriptor.*
import dk.cachet.carp.analytics.domain.data.DataSchema
import dk.cachet.carp.analytics.domain.data.InputDataSpec
import dk.cachet.carp.analytics.domain.data.OutputDataSpec
import dk.cachet.carp.analytics.domain.environment.EnvironmentDefinition
import dk.cachet.carp.analytics.domain.tasks.*
import dk.cachet.carp.analytics.domain.workflow.*
import dk.cachet.carp.common.application.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

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
 * [EnvironmentDefinition] is mapped through the known-kinds registry built from
 * [carp.dsp.core.application.environment.CondaEnvironmentDefinition] and [carp.dsp.core.application.environment.PixiEnvironmentDefinition]; unknown implementations
 * fall back to an empty spec with kind `"unknown"`.
 */
class WorkflowDescriptorExporter
{
    // ── Public entry points ───────────────────────────────────────────────────

    /**
     * Export a [WorkflowDefinition] (workflow + environment registry) to a [WorkflowDescriptor].
     *
     * Steps are exported in the order returned by [Workflow.getComponents].
     * Environments are sorted by their UUID key for deterministic output.
     */
    fun export( definition: WorkflowDefinition, schemaVersion: String = "1.0" ): WorkflowDescriptor =
        WorkflowDescriptor(
            schemaVersion = schemaVersion,
            metadata = exportWorkflowMetadata( definition.workflow.metadata ),
            steps = exportSteps( definition.workflow ),
            environments = exportEnvironments( definition.environments ),
        )

    // ── Metadata ──────────────────────────────────────────────────────────────

    internal fun exportWorkflowMetadata( meta: WorkflowMetadata ): WorkflowMetadataDescriptor =
        WorkflowMetadataDescriptor(
            id = meta.id.toString(),
            name = meta.name,
            description = meta.description,
            version = meta.version.toString(),
        )

    internal fun exportStepMetadata( meta: StepMetadata ): StepMetadataDescriptor =
        StepMetadataDescriptor(
            name = meta.name,
            description = meta.description,
            version = meta.version.toString(),
        )

    // ── Steps ─────────────────────────────────────────────────────────────────

    internal fun exportSteps( workflow: Workflow ): List<StepDescriptor> =
        workflow.getComponents()
            .filterIsInstance<Step>()
            .map { exportStep( it ) }

    internal fun exportStep( step: Step ): StepDescriptor =
        StepDescriptor(
            id = step.metadata.id.toString(),
            metadata = exportStepMetadata( step.metadata ),
            environmentId = step.environmentId.toString(),
            dependsOn = emptyList(), // dependency order is structural — not stored on Step currently
            task = exportTask( step.task ),
            inputs = step.inputs.map { exportInputPort( it ) },
            outputs = step.outputs.map { exportOutputPort( it ) },
        )

    // ── Data ports ────────────────────────────────────────────────────────────

    internal fun exportInputPort( spec: InputDataSpec ): DataPortDescriptor =
        DataPortDescriptor(
            id = spec.id.toString(),
            descriptor = exportDataSchema( spec.schema ),
        )

    internal fun exportOutputPort( spec: OutputDataSpec ): DataPortDescriptor =
        DataPortDescriptor(
            id = spec.id.toString(),
            descriptor = exportDataSchema( spec.schema ),
        )

    /**
     * Maps a [DataSchema] to a [DataDescriptor].
     *
     * **R1 simplification:** only `format` (lowercased enum name → `type`) and
     * `encoding` (→ `format`) are carried over. Fields such as [DataSchema.columns],
     * [DataSchema.jsonSchema], and [DataSchema.compression] are intentionally dropped
     * here and must be re-modelled in a richer [DataDescriptor] if needed in R2.
     */
    private fun exportDataSchema( schema: DataSchema? ): DataDescriptor? =
        schema?.let {
            DataDescriptor(
                type = it.format.name.lowercase(),
                format = it.encoding,
            )
        }

    // ── Tasks ─────────────────────────────────────────────────────────────────

    internal fun exportTask( task: TaskDefinition ): TaskDescriptor =
        when ( task )
        {
            is CommandTaskDefinition -> exportCommandTask( task )
            is PythonTaskDefinition  -> exportPythonTask( task )
            else -> throw UnsupportedTaskTypeException( task::class.simpleName ?: "unknown" )
        }

    internal fun exportCommandTask( task: CommandTaskDefinition ): CommandTaskDescriptor =
        CommandTaskDescriptor(
            id = task.id.toString(),
            name = task.name,
            description = task.description,
            executable = task.executable,
            args = task.args.map { exportArgToken( it ) },
        )

    internal fun exportPythonTask( task: PythonTaskDefinition ): PythonTaskDescriptor =
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

    // ── ArgTokens ─────────────────────────────────────────────────────────────

    internal fun exportArgToken( token: ArgToken ) =
        when ( token )
        {
            is Literal   -> LiteralArgDescriptor( token.value )
            is InputRef  -> InputRefArgDescriptor( token.inputId.toString() )
            is OutputRef -> OutputRefArgDescriptor( token.outputId.toString() )
            is ParamRef  -> ParamRefArgDescriptor( token.name )
        }

    // ── Environments ──────────────────────────────────────────────────────────

    /**
     * Exports environments sorted by UUID key for deterministic output.
     * The map key becomes the canonical environment id in the descriptor.
     */
    internal fun exportEnvironments(
        environments: Map<UUID, EnvironmentDefinition>
    ): Map<String, EnvironmentDescriptor> =
        environments.entries
            .sortedBy { it.key.toString() }
            .associate { ( uuid, env ) ->
                uuid.toString() to exportEnvironment( env )
            }

    internal fun exportEnvironment( env: EnvironmentDefinition ): EnvironmentDescriptor
    {
        val ( kind, spec ) = resolveKindAndSpec( env )
        return EnvironmentDescriptor(
            name = env.name,
            kind = kind,
            spec = spec,
        )
    }

    /**
     * Extracts a kind string and a [JsonElement]-valued spec map from a [EnvironmentDefinition].
     *
     * Known concrete types ([carp.dsp.core.application.environment.CondaEnvironmentDefinition],
     * [carp.dsp.core.application.environment.PixiEnvironmentDefinition]) get a rich spec.
     * Unknown implementations fall back to kind `"unknown"` with a base spec so the exporter
     * never throws on unfamiliar env types.
     *
     * **Coupling note:** detection relies on [kotlin.reflect.KClass.simpleName].
     * Renaming a concrete environment class will silently break detection and fall through to
     * `"unknown"`. If environment handling fully migrates into the DSP layer this logic should
     * move with it — likely as an `exportEnvironment()` method on each [EnvironmentDefinition]
     * implementation or via a registered `EnvironmentDescriptorSerializer` extension point.
     *
     * TODO(arch): replace simpleName dispatch with a type-safe extension point once
     *   environment implementations are complete.
     */
    private fun resolveKindAndSpec( env: EnvironmentDefinition ): Pair<String, Map<String, JsonElement>>
    {
        // Use the class simple name to detect known kinds without importing DSP-specific types
        // into this shared mapper, keeping it decoupled from concrete DSP implementations.
        val className = env::class.simpleName ?: ""

        val baseSpec: MutableMap<String, JsonElement> = mutableMapOf(
            "dependencies" to jsonStringArray( env.dependencies ),
        )

        env.environmentVariables.forEach { ( k, v ) ->
            baseSpec["env.$k"] = JsonPrimitive( v )
        }

        return when (className) {
            "CondaEnvironmentDefinition" -> {
                // Access extra fields reflectively via the interface contract + known cast
                val conda = env as? carp.dsp.core.application.environment.CondaEnvironmentDefinition
                if ( conda != null ) {
                    baseSpec["pythonVersion"] = JsonPrimitive( conda.pythonVersion )
                    baseSpec["channels"] = jsonStringArray( conda.channels )
                }
                "conda" to baseSpec
            }

            "PixiEnvironmentDefinition" -> {
                val pixi = env as? carp.dsp.core.application.environment.PixiEnvironmentDefinition
                if ( pixi != null ) {
                    baseSpec["pythonVersion"] = JsonPrimitive( pixi.pythonVersion )
                    baseSpec["channels"] = jsonStringArray( pixi.channels )
                }
                "pixi" to baseSpec
            }

            else -> "unknown" to baseSpec
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun jsonStringArray( items: List<String> ): JsonElement =
        kotlinx.serialization.json.JsonArray( items.map { JsonPrimitive( it ) } )
}

/**
 * Thrown when the exporter encounters a [TaskDefinition] subtype it does not know how to map.
 */
class UnsupportedTaskTypeException( typeName: String ) :
    IllegalArgumentException( "Cannot export TaskDefinition of type '$typeName'. " +
            "Register a handler in WorkflowDescriptorExporter.exportTask()." )
