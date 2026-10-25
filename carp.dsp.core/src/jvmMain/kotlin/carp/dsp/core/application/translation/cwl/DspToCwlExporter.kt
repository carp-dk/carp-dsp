package carp.dsp.core.application.translation.cwl

import carp.dsp.core.application.authoring.descriptor.CommandTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.DataPortDescriptor
import carp.dsp.core.application.authoring.descriptor.DefinedStepDescriptor
import carp.dsp.core.application.authoring.descriptor.EnvironmentDescriptor
import carp.dsp.core.application.authoring.descriptor.ExternalInputSource
import carp.dsp.core.application.authoring.descriptor.FileInputSource
import carp.dsp.core.application.authoring.descriptor.FileOutputDestination
import carp.dsp.core.application.authoring.descriptor.InProcessTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.InputSource
import carp.dsp.core.application.authoring.descriptor.ModuleEntryPointDescriptor
import carp.dsp.core.application.authoring.descriptor.PythonTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.RTaskDescriptor
import carp.dsp.core.application.authoring.descriptor.ScriptEntryPointDescriptor
import carp.dsp.core.application.authoring.descriptor.StepDescriptor
import carp.dsp.core.application.authoring.descriptor.StepOutputInputSource
import carp.dsp.core.application.authoring.descriptor.TaskDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor
import carp.dsp.core.application.translation.WorkflowExporter

/**
 * Exports a [WorkflowDescriptor] to CWL v1.2.
 *
 * [export] returns one `CommandLineTool` per step; [exportWorkflow] returns one `Workflow`
 * document with every tool inline and the steps wired by their declared data flow.
 *
 * Both run in any CWL v1.2 engine when the CWL file is placed in the workflow's package
 * folder (next to its scripts): every tool stages that folder's top-level entries
 * (input `package_files`, default `.`) into its working directory, as CARP-DSP does.
 * The software environment is not created: conda/pixi dependencies are `SoftwareRequirement`
 * hints, so the engine must run inside an environment that provides them. Docker
 * environments become a `DockerRequirement`.
 *
 * In-process steps and unresolved `uses:` steps have no CWL equivalent and are skipped.
 */
object DspToCwlExporter : WorkflowExporter<List<CwlDocument>>
{
    const val CWL_VERSION = "v1.2"
    private const val PACKAGE_FILES = "package_files"

    override fun export( descriptor: WorkflowDescriptor ): List<CwlDocument> =
        descriptor.steps.mapNotNull { step ->
            toolFor( step, descriptor.environments )?.let { tool ->
                CwlDocument( stepId = tool.stepId, content = document { appendTool( tool, "" ) } )
            }
        }

    /** Returns the whole workflow as one CWL `Workflow` document, or `null` if no step translates. */
    fun exportWorkflow( descriptor: WorkflowDescriptor ): CwlDocument?
    {
        val tools = descriptor.steps.mapNotNull { toolFor( it, descriptor.environments ) }
        if ( tools.isEmpty() ) return null
        val exported = tools.map { it.cwlId }.toSet()
        val content = document {
            appendLine( "class: Workflow" )
            appendLine( "label: ${q( oneLine( descriptor.metadata.name ) )}" )
            descriptor.metadata.description?.let { appendLine( "doc: ${q( oneLine( it ) )}" ) }
            appendWorkflowInputs( tools, exported )
            appendWorkflowOutputs( tools )
            appendLine( "steps:" )
            tools.forEach { appendWorkflowStep( it, exported ) }
        }
        val id = descriptor.metadata.id ?: descriptor.metadata.name
        return CwlDocument( stepId = id, content = content )
    }

    // -- Model ----------------------------------------------------------------

    private data class Port( val cwlId: String, val source: InputSource?, val fileName: String )

    private data class Tool(
        val stepId: String,
        val baseCommand: List<String>,
        val arguments: List<String>,
        val params: List<String>,
        val packages: List<String>,
        val envVars: List<Pair<String, String>>,
        val dockerImage: String?,
        val inputs: List<Port>,
        val outputs: List<Port>,
    )
    {
        val cwlId: String get() = stepId.cwlId()
    }

    private fun toolFor( step: StepDescriptor, environments: Map<String, EnvironmentDescriptor> ): Tool?
    {
        // A referenced step (`uses:`) has no inline task until resolved; skip it, as
        // with any step that has no command to translate.
        val defined = step as? DefinedStepDescriptor ?: return null
        val ( baseCommand, args ) = commandFor( defined.task ) ?: return null
        val env = environments[ defined.environmentId ]
        val dockerImage = if ( env?.kind == "docker" ) env.spec[ "image" ]?.firstOrNull() else null
        val packages =
            if ( dockerImage == null && env?.kind in listOf( "conda", "pixi" ) ) env?.spec?.get( "dependencies" )
            else null
        val envVars = env?.spec?.get( "env" ).orEmpty().mapNotNull { entry ->
            entry.split( "=", limit = 2 ).takeIf { it.size == 2 }?.let { it[ 0 ] to it[ 1 ] }
        }
        val inputs = defined.inputs.mapIndexed { i, port ->
            Port( ( port.id ?: "input_$i" ).cwlId(), port.source, "" )
        }
        val outputs = defined.outputs.mapIndexed { i, port ->
            Port( ( port.id ?: "output_$i" ).cwlId(), null, fileName( port, i ) )
        }
        return Tool(
            stepId = step.id ?: defined.task.name,
            baseCommand = baseCommand,
            arguments = args.map { argument( it, inputs, outputs ) },
            params = args.mapNotNull { PARAM.matchEntire( it )?.groupValues?.get( 1 ) }.distinct(),
            packages = packages.orEmpty(),
            envVars = envVars,
            dockerImage = dockerImage,
            inputs = inputs,
            outputs = outputs,
        )
    }

    private fun commandFor( task: TaskDescriptor ): Pair<List<String>, List<String>>? = when ( task )
    {
        is CommandTaskDescriptor -> listOf( task.executable ) to task.args
        is PythonTaskDescriptor -> when ( val ep = task.entryPoint )
        {
            is ScriptEntryPointDescriptor -> listOf( "python", ep.scriptPath ) to task.args
            is ModuleEntryPointDescriptor -> listOf( "python", "-m", ep.moduleName ) to task.args
        }
        is RTaskDescriptor -> listOf( "Rscript", task.entryPoint.scriptPath ) to task.args
        is InProcessTaskDescriptor -> null
    }

    // `input.N` and `output.N` are CARP-DSP's port placeholders; `param:NAME` a parameter.
    private val INPUT = Regex( "^input\\.(\\d+)$" )
    private val OUTPUT = Regex( "^output\\.(\\d+)$" )
    private val PARAM = Regex( "^param:([a-zA-Z0-9_]+)$" )

    private fun argument( arg: String, inputs: List<Port>, outputs: List<Port> ): String
    {
        val input = INPUT.matchEntire( arg )?.let { inputs.getOrNull( it.groupValues[ 1 ].toInt() ) }
        val output = OUTPUT.matchEntire( arg )?.let { outputs.getOrNull( it.groupValues[ 1 ].toInt() ) }
        val param = PARAM.matchEntire( arg )?.groupValues?.get( 1 )
        return when
        {
            input != null -> "$(inputs.${input.cwlId}.path)"
            output != null -> output.fileName
            param != null -> "$(inputs.$param)"
            else -> arg
        }
    }

    // Outputs are written to the tool's working directory under a plain file name.
    private fun fileName( port: DataPortDescriptor, index: Int ): String
    {
        ( port.destination as? FileOutputDestination )?.let { return it.path.substringAfterLast( '/' ) }
        val base = port.id ?: "output_$index"
        return port.descriptor?.fileFormat?.let { "$base.$it" } ?: base
    }

    // -- Tool YAML ------------------------------------------------------------

    private fun StringBuilder.appendTool( tool: Tool, p: String )
    {
        appendLine( "${p}class: CommandLineTool" )
        appendLine( "${p}baseCommand:" )
        tool.baseCommand.forEach { appendLine( "$p  - ${q( it )}" ) }
        if ( tool.arguments.isNotEmpty() )
        {
            appendLine( "${p}arguments:" )
            tool.arguments.forEach { appendLine( "$p  - ${q( it )}" ) }
        }
        appendRequirements( tool, p )
        appendHints( tool, p )
        appendToolInputs( tool, p )
        appendToolOutputs( tool, p )
    }

    private fun StringBuilder.appendRequirements( tool: Tool, p: String )
    {
        appendLine( "${p}requirements:" )
        appendLine( "$p  LoadListingRequirement:" )
        appendLine( "$p    loadListing: shallow_listing" )
        appendLine( "$p  InitialWorkDirRequirement:" )
        appendLine( "$p    listing: $(inputs.$PACKAGE_FILES.listing)" )
        tool.dockerImage?.let {
            appendLine( "$p  DockerRequirement:" )
            appendLine( "$p    dockerPull: ${q( it )}" )
        }
        if ( tool.envVars.isNotEmpty() )
        {
            appendLine( "$p  EnvVarRequirement:" )
            appendLine( "$p    envDef:" )
            tool.envVars.forEach { ( k, v ) ->
                appendLine( "$p      - envName: ${q( k )}" )
                appendLine( "$p        envValue: ${q( v )}" )
            }
        }
    }

    // SoftwareRequirement is advisory - it goes in hints (cwltool rejects it in requirements).
    private fun StringBuilder.appendHints( tool: Tool, p: String )
    {
        if ( tool.packages.isEmpty() ) return
        appendLine( "${p}hints:" )
        appendLine( "$p  SoftwareRequirement:" )
        appendLine( "$p    packages:" )
        tool.packages.forEach { appendLine( "$p      - package: ${q( it )}" ) }
    }

    private fun StringBuilder.appendToolInputs( tool: Tool, p: String )
    {
        appendLine( "${p}inputs:" )
        appendLine( "$p  $PACKAGE_FILES:" )
        appendLine( "$p    type: Directory" )
        appendLine( "$p    default:" )
        appendLine( "$p      class: Directory" )
        appendLine( "$p      location: ." )
        tool.inputs.forEach { appendLine( "$p  ${it.cwlId}: File" ) }
        tool.params.forEach { appendLine( "$p  $it: string" ) }
    }

    private fun StringBuilder.appendToolOutputs( tool: Tool, p: String )
    {
        if ( tool.outputs.isEmpty() )
        {
            appendLine( "${p}outputs: {}" )
            return
        }
        appendLine( "${p}outputs:" )
        tool.outputs.forEach {
            appendLine( "$p  ${it.cwlId}:" )
            appendLine( "$p    type: File" )
            appendLine( "$p    outputBinding:" )
            appendLine( "$p      glob: ${q( it.fileName )}" )
        }
    }

    // -- Workflow YAML --------------------------------------------------------

    // A step input fed by another exported step is wired to it; anything else becomes a
    // workflow input, with a default when the source names a file or URL.
    private fun upstream( source: InputSource?, exported: Set<String> ): String? =
        ( source as? StepOutputInputSource )
            ?.takeIf { it.stepId.cwlId() in exported }
            ?.let { "${it.stepId.cwlId()}/${it.outputId.cwlId()}" }

    private fun StringBuilder.appendWorkflowInputs( tools: List<Tool>, exported: Set<String> )
    {
        appendLine( "inputs:" )
        appendLine( "  $PACKAGE_FILES:" )
        appendLine( "    type: Directory" )
        appendLine( "    default:" )
        appendLine( "      class: Directory" )
        appendLine( "      location: ." )
        tools.forEach { tool ->
            tool.inputs.filter { upstream( it.source, exported ) == null }.forEach { port ->
                val location = when ( val s = port.source )
                {
                    is FileInputSource -> s.path
                    is ExternalInputSource -> s.uri
                    else -> null
                }
                appendLine( "  ${tool.cwlId}__${port.cwlId}:" )
                appendLine( "    type: File" )
                location?.let {
                    appendLine( "    default:" )
                    appendLine( "      class: File" )
                    appendLine( "      location: ${q( it )}" )
                }
            }
            tool.params.forEach { appendLine( "  ${tool.cwlId}__$it: string" ) }
        }
    }

    private fun StringBuilder.appendWorkflowOutputs( tools: List<Tool> )
    {
        val outputs = tools.flatMap { tool -> tool.outputs.map { tool.cwlId to it.cwlId } }
        if ( outputs.isEmpty() )
        {
            appendLine( "outputs: {}" )
            return
        }
        appendLine( "outputs:" )
        outputs.forEach { ( step, port ) ->
            appendLine( "  ${step}__$port:" )
            appendLine( "    type: File" )
            appendLine( "    outputSource: $step/$port" )
        }
    }

    // CWL orders steps by data flow only; a `dependsOn` without a data edge is not kept.
    private fun StringBuilder.appendWorkflowStep( tool: Tool, exported: Set<String> )
    {
        appendLine( "  ${tool.cwlId}:" )
        appendLine( "    in:" )
        appendLine( "      $PACKAGE_FILES: $PACKAGE_FILES" )
        tool.inputs.forEach { port ->
            appendLine( "      ${port.cwlId}: ${upstream( port.source, exported ) ?: "${tool.cwlId}__${port.cwlId}"}" )
        }
        tool.params.forEach { appendLine( "      $it: ${tool.cwlId}__$it" ) }
        appendLine( "    out: [${tool.outputs.joinToString( ", " ) { it.cwlId }}]" )
        appendLine( "    run:" )
        appendTool( tool, "      " )
    }

    // -- Helpers --------------------------------------------------------------

    private fun document( body: StringBuilder.() -> Unit ): String =
        buildString {
            appendLine( "cwlVersion: $CWL_VERSION" )
            body()
        }.trimEnd()

    private fun oneLine( s: String ): String = s.replace( Regex( "\\s+" ), " " ).trim()

    private fun q( s: String ): String =
        if ( needsQuoting( s ) ) "\"${s.replace( "\\", "\\\\" ).replace( "\"", "\\\"" )}\"" else s

    private fun needsQuoting( s: String ): Boolean =
        s.isEmpty() || s.any { it in ":{}[]#&*?|>!'\"@`," } || s.first().isWhitespace() || s.last().isWhitespace()

    private fun String.cwlId(): String = replace( "-", "_" )
}
