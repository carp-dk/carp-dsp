package carp.dsp.core.application.authoring.resolve

import carp.dsp.core.application.authoring.descriptor.CommandTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.DataPortDescriptor
import carp.dsp.core.application.authoring.descriptor.DefinedStepDescriptor
import carp.dsp.core.application.authoring.descriptor.EnvironmentDescriptor
import carp.dsp.core.application.authoring.descriptor.InProcessTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.PythonTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.RTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.ReferencedStepDescriptor
import carp.dsp.core.application.authoring.descriptor.TaskDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor

/**
 * Thrown when a `uses:` reference cannot be resolved against the library.
 */
class UsesResolutionException(
    val reference: ReferencedStepDescriptor,
    reason: String,
) : Exception(
    "Cannot resolve step '${reference.uses}'" +
        ( reference.version?.let { " (version $it)" } ?: "" ) + ": $reason"
)

/**
 * The result of resolving a workflow: the workflow with every reference expanded,
 * the lock recording what each reference resolved to, and the implementation files
 * each resolved step brought in from the library.
 *
 * @property impl Resolved step id -> the step's impl files (path relative to the
 *   step dir -> content). Only library-sourced steps appear; inline steps bring
 *   no impl. Staged into each step's workspace at execution.
 */
data class ResolutionResult(
    val workflow: WorkflowDescriptor,
    val lock: StepsLock,
    val impl: Map<String, Map<String, String>> = emptyMap(),
)

/**
 * Expands every `uses:` reference in a workflow into a self-contained step.
 *
 * A [ReferencedStepDescriptor] carries only wiring - id, ordering and input
 * sources; the resolver looks the step up in the [library] and produces a
 * [DefinedStepDescriptor] by taking the library step's task, environment and
 * ports and overlaying the consumer's wiring on top. Environments the library
 * step needs are merged into the workflow so the resolved step's `environmentId`
 * resolves.
 *
 * Resolution runs before linting and import, so downstream stages only ever see
 * defined steps. A [DefinedStepDescriptor] passes through untouched.
 *
 * Resolution is recorded in a [StepsLock]. Passing an existing lock pins each
 * reference to its locked version (unless the reference names an explicit
 * version, which wins) and verifies the resolved content hash against the lock,
 * so re-planning is deterministic and library drift is caught.
 */
class UsesResolver( private val library: StepLibrary )
{
    fun resolve( descriptor: WorkflowDescriptor, lock: StepsLock? = null ): ResolutionResult
    {
        if ( descriptor.steps.none { it is ReferencedStepDescriptor } )
        {
            return ResolutionResult( descriptor, StepsLock() )
        }

        val environments = descriptor.environments.toMutableMap()
        val locked = LinkedHashMap<String, LockedStep>()
        val impl = LinkedHashMap<String, Map<String, String>>()
        val steps = descriptor.steps.map { step ->
            when ( step )
            {
                is DefinedStepDescriptor -> step
                is ReferencedStepDescriptor ->
                {
                    val expansion = expand( step, environments, lock )
                    if ( expansion.locked.uses !in locked ) locked[expansion.locked.uses] = expansion.locked
                    expansion.step.id?.let { id ->
                        if ( expansion.impl.isNotEmpty() ) impl[id] = expansion.impl
                    }
                    expansion.step
                }
            }
        }
        return ResolutionResult(
            descriptor.copy( steps = steps, environments = environments ),
            StepsLock( locked.values.toList() ),
            impl,
        )
    }

    /** A resolved reference: the defined step, its lock entry, and its impl files. */
    private data class Expansion(
        val step: DefinedStepDescriptor,
        val locked: LockedStep,
        val impl: Map<String, String>,
    )

    private fun expand(
        reference: ReferencedStepDescriptor,
        environments: MutableMap<String, EnvironmentDescriptor>,
        lock: StepsLock?,
    ): Expansion
    {
        val pinned = lock?.entryFor( reference.uses )
        // Precedence: an explicit reference version wins, else the locked version, else latest.
        val requestedVersion = reference.version ?: pinned?.version

        val resolved = library.lookup( reference.uses, requestedVersion )
            ?: throw UsesResolutionException( reference, "not found in the library" )

        // Verify the hash only when we pinned from the lock (no explicit override):
        // a mismatch means the library served different bytes under the same version.
        if ( reference.version == null && pinned != null && pinned.contentHash != resolved.contentHash )
        {
            throw UsesResolutionException(
                reference,
                "content hash ${resolved.contentHash} does not match the locked ${pinned.contentHash} " +
                    "- the library served different bytes under version ${resolved.version}"
            )
        }

        mergeEnvironments( reference, resolved.environments, environments )

        val libraryStep = resolved.step
        // Consumer args (when given) overlay the library task's args flag-by-flag.
        val task = reference.args?.let { withArgs( libraryStep.task, mergeArgs( argsOf( libraryStep.task ), it ) ) }
            ?: libraryStep.task
        val defined = libraryStep.copy(
            // Consumer wiring overrides; environment and outputs come from the library.
            id = reference.id ?: libraryStep.id,
            metadata = reference.metadata ?: libraryStep.metadata,
            dependsOn = reference.dependsOn,
            task = task,
            inputs = bindInputs( libraryStep.inputs, reference ),
        )
        return Expansion(
            defined,
            LockedStep( reference.uses, resolved.version, resolved.contentHash ),
            resolved.implFiles,
        )
    }

    /** The task's argument list, or empty for a task type that carries none. */
    private fun argsOf( task: TaskDescriptor ): List<String> = when ( task )
    {
        is CommandTaskDescriptor -> task.args
        is PythonTaskDescriptor -> task.args
        is RTaskDescriptor -> task.args
        is InProcessTaskDescriptor -> emptyList()
    }

    /** Copy [task] with [newArgs]; a task type without an arg list is returned unchanged. */
    private fun withArgs( task: TaskDescriptor, newArgs: List<String> ): TaskDescriptor = when ( task )
    {
        is CommandTaskDescriptor -> task.copy( args = newArgs )
        is PythonTaskDescriptor -> task.copy( args = newArgs )
        is RTaskDescriptor -> task.copy( args = newArgs )
        is InProcessTaskDescriptor -> task
    }

    /**
     * Overlay [override] flags onto [base], flag-by-flag, preserving base order.
     * A `--flag` in [override] replaces the base value for that flag; flags only
     * in [base] are kept; flags only in [override] are appended.
     */
    private fun mergeArgs( base: List<String>, override: List<String> ): List<String>
    {
        val overrides = parseFlags( override )
        val consumed = mutableSetOf<String>()
        val result = mutableListOf<String>()
        var i = 0
        while ( i < base.size )
        {
            val tok = base[i]
            val hadValue = i + 1 < base.size && !base[i + 1].startsWith( "--" )
            if ( tok.startsWith( "--" ) && tok in overrides )
            {
                result.add( tok )
                overrides[tok]?.let { result.add( it ) }
                consumed.add( tok )
                i += if ( hadValue ) 2 else 1
            }
            else
            {
                result.add( tok )
                i += 1
            }
        }
        overrides.forEach { ( flag, value ) ->
            if ( flag !in consumed ) {
                result.add( flag )
            value?.let { result.add( it ) }
            }
        }
        return result
    }

    /** Parse a flat arg list into ordered `--flag -> value?` pairs. */
    private fun parseFlags( args: List<String> ): LinkedHashMap<String, String?>
    {
        val map = LinkedHashMap<String, String?>()
        var i = 0
        while ( i < args.size )
        {
            val tok = args[i]
            if ( tok.startsWith( "--" ) )
            {
                val value = if ( i + 1 < args.size && !args[i + 1].startsWith( "--" ) ) args[i + 1] else null
                map[tok] = value
                i += if ( value != null ) 2 else 1
            }
            else i += 1
        }
        return map
    }

    /** Overlay each consumer `source` onto the matching library input port, by port id. */
    private fun bindInputs(
        libraryInputs: List<DataPortDescriptor>,
        reference: ReferencedStepDescriptor,
    ) = libraryInputs.map { libraryPort ->
        val binding = reference.inputs.firstOrNull { it.id == libraryPort.id }
        if ( binding?.source != null ) libraryPort.copy( source = binding.source ) else libraryPort
    }

    /** Add the library step's environments to the workflow, rejecting a genuine id clash. */
    private fun mergeEnvironments(
        reference: ReferencedStepDescriptor,
        libraryEnvironments: Map<String, EnvironmentDescriptor>,
        into: MutableMap<String, EnvironmentDescriptor>,
    )
    {
        libraryEnvironments.forEach { ( id, environment ) ->
            val existing = into[id]
            if ( existing != null && existing != environment )
            {
                throw UsesResolutionException(
                    reference,
                    "environment '$id' clashes with a different environment already in the workflow"
                )
            }
            into[id] = environment
        }
    }
}
