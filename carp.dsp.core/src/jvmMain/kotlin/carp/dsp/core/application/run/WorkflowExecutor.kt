package carp.dsp.core.application.run

import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor
import carp.dsp.core.application.authoring.mapper.WorkflowDescriptorImporter
import carp.dsp.core.application.authoring.resolve.StepLibrary
import carp.dsp.core.application.authoring.resolve.WorkflowResolution
import carp.dsp.core.application.execution.ExecutionLogger
import carp.dsp.core.application.execution.NoOpExecutionLogger
import carp.dsp.core.application.plan.DefaultExecutionPlanner
import carp.dsp.core.application.plan.ProtocolDataTypeProvider
import carp.dsp.core.infrastructure.execution.DefaultPlanExecutor
import carp.dsp.core.infrastructure.execution.FileSystemArtefactStore
import carp.dsp.core.infrastructure.execution.workspace.DefaultWorkspaceManager
import carp.dsp.core.infrastructure.execution.workspace.FileSource
import carp.dsp.core.infrastructure.execution.workspace.WorkspaceProvisioning
import carp.dsp.core.infrastructure.serialization.WorkflowYamlCodec
import dk.cachet.carp.analytics.application.execution.ArtefactStore
import dk.cachet.carp.analytics.application.execution.DefaultRunPolicy
import dk.cachet.carp.analytics.application.execution.ExecutionReport
import dk.cachet.carp.analytics.application.execution.RunPolicy
import dk.cachet.carp.analytics.application.execution.workspace.WorkspaceManager
import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import dk.cachet.carp.analytics.domain.data.FileLocation
import dk.cachet.carp.analytics.domain.data.InputDataSpec
import dk.cachet.carp.analytics.domain.workflow.Step
import dk.cachet.carp.analytics.domain.workflow.WorkflowDefinition
import dk.cachet.carp.common.application.UUID
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * A workflow that has been fully prepared for execution.
 *
 * Contains the expanded workflow descriptor, resolved domain definition,
 * execution plan, and workspace provisioning required to run the workflow.
 *
 * @property descriptor The workflow with every `uses:` reference expanded. Use
 *   this, not the authored descriptor, for anything downstream that must see the
 *   whole workflow - packaging and translation would otherwise skip referenced
 *   steps and emit an incomplete artefact.
 */
data class PreparedWorkflow(
    val descriptor: WorkflowDescriptor,
    val definition: WorkflowDefinition,
    val plan: ExecutionPlan,
    val provisioning: WorkspaceProvisioning,
)

/**
 * Runs a workflow through the whole life cycle: decode, resolve, import, plan,
 * provision, execute.
 *
 * The stages are encoded by the classes - [WorkflowYamlCodec], [WorkflowResolution],
 * [WorkflowDescriptorImporter], [DefaultExecutionPlanner], [DefaultPlanExecutor].
 * This composes them to provide a golden path for execution.
 *
 * [prepare] and [run] stay separate calls, because a caller may want to validate
 * a workflow without executing it.
 *
 * One run per call, synchronously. Queueing, cancellation and live status belong
 * to whatever hosts this.
 *
 * @param stepLibrary Where `uses:` references are looked up. Injected because the
 *   library ships in `carp.dsp.steps`, which this module does not depend on.
 * @param workspaceManager Materialises the run workspace on disk.
 * @param artefactStore Records the outputs each step produced.
 */
class WorkflowExecutor(
    private val stepLibrary: StepLibrary,
    private val workspaceManager: WorkspaceManager,
    private val artefactStore: ArtefactStore,
    private val options: Options = Options(),
)
{
    /**
     * @property executionLogger Receives step lifecycle events, e.g. a console
     *   printer for the CLI, a run registry for a service.
     * @property protocolDataTypeProvider Supplies the study protocol's collected
     *   data types, so protocol-bound inputs are checked at plan time.
     * @property runPolicy Stop-on-failure, timeouts and retries.
     * @property workflowNamespace Fixes the UUID namespace the importer derives
     *   step ids from. Set it when plan ids must be reproducible across runs;
     *   left null, each import gets a fresh namespace.
     * @property executorOptions Passed to [DefaultPlanExecutor]. Override it to
     *   supply a command runner, an output validation policy, or - for a service
     *   running more than one workflow - an environment orchestrator that is not
     *   the process-wide default. [executionLogger] always wins over the logger
     *   set here.
     */
    data class Options(
        val executionLogger: ExecutionLogger = NoOpExecutionLogger,
        val protocolDataTypeProvider: ProtocolDataTypeProvider? = null,
        val runPolicy: RunPolicy = DefaultRunPolicy(),
        val workflowNamespace: UUID? = null,
        val executorOptions: DefaultPlanExecutor.Options = DefaultPlanExecutor.Options(),
    )

    private val codec = WorkflowYamlCodec()

    /**
     * Reads, resolves, imports and plans the workflow at [source], and defines
     * what the run must stage.
     *
     * Writes or updates the `steps.lock` beside the workflow; only required when
     * using step library dependencies with `uses:` keyword.
     *
     * @throws Exception if the file cannot be read, the YAML cannot be parsed, or
     *   a `uses:` reference cannot be resolved. A workflow that parses but is not
     *   runnable does *not* throw - it comes back as a plan carrying errors.
     */
    fun prepare( source: WorkflowSource ): PreparedWorkflow =
        prepare( source, codec.decodeOrThrow( source.file.readText() ) )

    /**
     * [prepare] for a package: the workflow plus the files it ships with.
     *
     * A step that names `scripts/clean.py` gets that file staged into the run.
     * Package files are staged alongside whatever resolution pulled out of the
     * library, and a package file never silently replaces a library one - the
     * provisioner refuses two different files at the same path.
     */
    fun prepare( pkg: WorkflowPackage ): PreparedWorkflow =
        prepare( pkg.source, codec.decodeOrThrow( pkg.source.file.readText() ), pkg.files )

    /**
     * [prepare], for a descriptor already parsed elsewhere.
     *
     * @param packageFiles destination to source: the key is where the file has to
     *   appear, as a path relative to the execution root - the same string a step
     *   uses to reach it, `scripts/clean.py` - and the value is where it is stored
     *   now. Directories are part of the key; it is a path, not a file name.
     */
    fun prepare(
        source: WorkflowSource,
        descriptor: WorkflowDescriptor,
        packageFiles: Map<String, Path> = emptyMap(),
    ): PreparedWorkflow
    {
        val resolved = WorkflowResolution.resolve( source.file, descriptor, stepLibrary )
        val importer = options.workflowNamespace
            ?.let { WorkflowDescriptorImporter( it ) }
            ?: WorkflowDescriptorImporter()
        val definition = importer.import( resolved.workflow )
        val plan = DefaultExecutionPlanner( options.protocolDataTypeProvider ).plan( definition )

        return PreparedWorkflow(
            descriptor = resolved.workflow,
            definition = definition,
            plan = plan,
            provisioning = provisioningFor( source.directory, definition, plan, resolved.impl, packageFiles ),
        )
    }

    /**
     * Executes [prepared], staging its files into the run workspace first.
     *
     * A plan carrying errors is refused rather than run: the returned report has
     * status FAILED and an issue naming them. Nothing is created and no step runs.
     */
    fun run( prepared: PreparedWorkflow, runId: UUID = UUID.randomUUID() ): ExecutionReport =
        DefaultPlanExecutor(
            workspaceManager = workspaceManager,
            artefactStore = artefactStore,
            options = options.executorOptions.copy( executionLogger = options.executionLogger ),
        ).run( prepared.plan, runId, prepared.provisioning, options.runPolicy )

    /** [prepare] then [run]. */
    fun run( source: WorkflowSource, runId: UUID = UUID.randomUUID() ): ExecutionReport =
        run( prepare( source ), runId )

    /**
     * Builds the workspace provisioning plan for each workflow step.
     *
     * Every step receives:
     * - implementation files resolved from the component library, and
     * - workflow input files referenced via relative paths.
     *
     * Relative input paths are resolved against [workflowDir] and added as
     * copy operations in the resulting [WorkspaceProvisioning].
     */
    private fun provisioningFor(
        workflowDir: Path,
        definition: WorkflowDefinition,
        plan: ExecutionPlan,
        impl: Map<String, Map<String, String>>,
        /** Destination relative to the execution root, to the file to copy there. */
        packageFiles: Map<String, Path>,
    ): WorkspaceProvisioning
    {
        // Each planned step carries its descriptor id, so the implementation files
        // the resolver collected (keyed by descriptor id) map onto planned steps
        // without a separate lookup being threaded through.
        val stepIdByDescriptor = plan.steps
            .mapNotNull { step -> step.metadata.descriptorId?.let { it to step.metadata.id } }
            .toMap()
        val byStep = HashMap<UUID, MutableMap<String, FileSource>>()

        impl.forEach { ( descriptorId, files ) ->
            val stepId = stepIdByDescriptor[descriptorId] ?: return@forEach
            val into = byStep.getOrPut( stepId ) { mutableMapOf() }
            files.forEach { ( path, content ) -> into[path] = FileSource.Content( content ) }
        }

        definition.workflow.getComponents().filterIsInstance<Step>().forEach { step ->
            step.inputs.mapNotNull( ::relativeInputPath ).forEach { relative ->
                byStep.getOrPut( step.metadata.id ) { mutableMapOf() }[relative] =
                    FileSource.Copy( workflowDir.resolve( relative ) )
            }
        }

        // Package files belong to the run rather than to any one step - commands
        // share the execution root - so they are attributed to the first step and
        // staged once.
        plan.steps.firstOrNull()?.let { first ->
            val into = byStep.getOrPut( first.metadata.id ) { mutableMapOf() }
            packageFiles.forEach { ( relative, file ) -> into[relative] = FileSource.Copy( file ) }
        }

        return WorkspaceProvisioning( byStep.mapValues { it.value.toMap() } )
    }

    /**
     * Returns the input path when it is relative to the workflow file; otherwise,
     * null for step outputs, non-file locations, and absolute paths that need no
     * staging.
     */
    private fun relativeInputPath( input: InputDataSpec ): String?
    {
        if ( input.stepRef != null ) return null
        val location = input.location as? FileLocation ?: return null
        val path = location.path.trim()
        if ( path.isEmpty() || File( path ).isAbsolute ) return null
        return path.removePrefix( "./" )
    }

    companion object
    {
        /**
         * An executor writing runs and artefacts under [workspaceRoot].
         *
         * The workspace manager rejects a relative root, so the path is made
         * absolute and created here.
         */
        fun filesystem(
            stepLibrary: StepLibrary,
            workspaceRoot: Path,
            options: Options = Options(),
        ): WorkflowExecutor
        {
            val root = workspaceRoot.toAbsolutePath()
            root.createDirectories()
            return WorkflowExecutor(
                stepLibrary = stepLibrary,
                workspaceManager = DefaultWorkspaceManager( root ),
                artefactStore = FileSystemArtefactStore( root.resolve( "artifacts" ) ),
                options = options,
            )
        }
    }
}
