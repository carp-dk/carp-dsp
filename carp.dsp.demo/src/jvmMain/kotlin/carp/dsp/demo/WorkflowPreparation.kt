package carp.dsp.demo

import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor
import carp.dsp.core.application.authoring.mapper.WorkflowDescriptorImporter
import carp.dsp.core.application.authoring.resolve.WorkflowResolution
import carp.dsp.core.application.plan.DefaultExecutionPlanner
import carp.dsp.core.infrastructure.execution.workspace.FileSource
import carp.dsp.core.infrastructure.execution.workspace.WorkspaceProvisioning
import carp.dsp.steps.ClasspathStepLibrary
import dk.cachet.carp.analytics.application.plan.ExecutionPlan
import dk.cachet.carp.analytics.domain.data.FileLocation
import dk.cachet.carp.analytics.domain.workflow.Step
import dk.cachet.carp.analytics.domain.workflow.WorkflowDefinition
import dk.cachet.carp.common.application.UUID
import java.io.File

/**
 * A workflow taken from a parsed descriptor to something runnable: the domain
 * definition, the plan, and the files the run needs staged.
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
 * Resolve, import and plan a workflow, and collect what the run must provision.
 *
 * Shared by the workflow runner and the demos so they agree on the pipeline. A
 * workflow that references library steps needs all three stages: resolution
 * expands the references, and provisioning carries the library implementations
 * and declared input files into the run. Importing a descriptor directly - as a
 * demo predating `uses:` would - fails on the first reference it meets.
 */
object WorkflowPreparation
{
    /**
     * Prepares [descriptor], read from [workflowFile].
     *
     * The file locates two things: the `steps.lock` that pins each reference, and
     * the directory that a relative file input is resolved against.
     */
    fun prepare( workflowFile: File, descriptor: WorkflowDescriptor ): PreparedWorkflow
    {
        val resolved = WorkflowResolution.resolve( workflowFile, descriptor, ClasspathStepLibrary() )
        val definition = WorkflowDescriptorImporter().import( resolved.workflow )
        val plan = DefaultExecutionPlanner().plan( definition )

        // Each planned step carries its descriptor id, so the implementation files
        // the resolver collected (keyed by descriptor id) map onto planned steps
        // without a separate lookup being threaded through.
        val stepIdByDescriptor = plan.steps
            .mapNotNull { step -> step.metadata.descriptorId?.let { it to step.metadata.id } }
            .toMap()
        val byStep = HashMap<UUID, MutableMap<String, FileSource>>()

        resolved.impl.forEach { ( descriptorId, files ) ->
            val stepId = stepIdByDescriptor[descriptorId] ?: return@forEach
            val into = byStep.getOrPut( stepId ) { mutableMapOf() }
            files.forEach { ( path, content ) -> into[path] = FileSource.Content( content ) }
        }

        val workflowDir = workflowFile.absoluteFile.parentFile
        definition.workflow.getComponents().filterIsInstance<Step>().forEach { step ->
            step.inputs.forEach { input ->
                val location = input.location
                if ( location is FileLocation && input.stepRef == null && location.path.isNotBlank() &&
                    !File( location.path ).isAbsolute
                )
                {
                    val relative = location.path.removePrefix( "./" )
                    byStep.getOrPut( step.metadata.id ) { mutableMapOf() }[relative] =
                        FileSource.Copy( File( workflowDir, location.path ).toPath() )
                }
            }
        }

        return PreparedWorkflow(
            descriptor = resolved.workflow,
            definition = definition,
            plan = plan,
            provisioning = WorkspaceProvisioning( byStep.mapValues { it.value.toMap() } ),
        )
    }
}
