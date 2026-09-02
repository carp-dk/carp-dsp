package carp.dsp.core.application.authoring.mapper

import carp.dsp.core.application.authoring.descriptor.DefinedStepDescriptor
import carp.dsp.core.application.authoring.descriptor.StepDescriptor
import carp.dsp.core.application.authoring.descriptor.WorkflowDescriptor
import dk.cachet.carp.analytics.domain.workflow.Step
import dk.cachet.carp.analytics.domain.workflow.Workflow
import dk.cachet.carp.analytics.domain.workflow.WorkflowDefinition
import dk.cachet.carp.common.application.UUID

/**
 * Converts [WorkflowDescriptor] DTOs to domain [WorkflowDefinition] models.
 *
 * This is the **lenient-read** boundary:
 * - Missing IDs are generated via deterministic UUID v5 hashing
 * - Human-readable environment keys (e.g. `"env-conda-001"`) are mapped to UUIDs
 * - Missing `schemaVersion` / `version` strings default to `"1.0"`
 * - Unknown environment kinds throw [dk.cachet.carp.analytics.application.exceptions.UnsupportedEnvironmentKindException]
 *
 * @param workflowNamespace Optional namespace UUID for deterministic ID generation.
 *   If not provided, a random UUID is generated (default behaviour for new workflows).
 *   For reproducibility, pass a fixed UUID derived from the workflow ID.
 *
 * ### Sub-importers
 *
 * Delegates to focused sub-importers:
 * - [MetadataImporter] — workflow / step metadata
 * - [PortImporter]     — input / output data ports
 * - [TaskImporter]     — task definitions and arg tokens
 * - [EnvironmentImporter] — environment definitions
 *
 * ### Port Placeholder Strategy
 *
 * Data port source/destination cannot be reconstructed from a [WorkflowDescriptor] alone.
 * [PortImporter] inserts placeholders so that port IDs and schemas are
 * preserved while the source/destination data is intentionally dropped.
 * This is a limitation; full InputSource will be added.
 */
class WorkflowDescriptorImporter(
    private val workflowNamespace: UUID = UUID.randomUUID()
)
{
    /**
     * Imports a workflow descriptor to a domain workflow definition.
     *
     * @param workflowDescriptor The workflow descriptor (from YAML)
     * @return Domain WorkflowDefinition (ready for planning)
     */
    fun import(workflowDescriptor: WorkflowDescriptor ): WorkflowDefinition
    {
        // Import environments first (builds UUID lookup for environment references)
        val ( environments, environmentKeyToUuid ) =
            EnvironmentImporter.importEnvironments( workflowDescriptor.environments, workflowNamespace )

        // Import workflow metadata (with deterministic UUID)
        val workflowMetadata = MetadataImporter.importWorkflowMetadata(
            workflowDescriptor.metadata,
            workflowNamespace
        )

        // Build workflow. Each step's descriptor id is carried onto its StepMetadata
        // (StepMetadata.descriptorId), so downstream stages can tie descriptor-keyed
        // data to the planned step without a separate id map.
        val workflow = Workflow(metadata = workflowMetadata)
        workflowDescriptor.steps.forEach { stepDesc ->
            workflow.addComponent( importStepInternal( stepDesc, environmentKeyToUuid ) )
        }

        return WorkflowDefinition( workflow = workflow, environments = environments )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Internal step importer that uses the environment lookup table.
     *
     * @param stepDescriptor The step descriptor
     * @param environmentKeyToUuid Lookup: environment key → UUID (from EnvironmentImporter)
     * @return Domain Step
     */
    private fun importStepInternal(
        stepDescriptor: StepDescriptor,
        environmentKeyToUuid: Map<String, UUID>,
    ): Step
    {
        // A referenced step (`uses:`) must be resolved into a defined step before import.
        val step = stepDescriptor as? DefinedStepDescriptor
            ?: error(
                "Step '${stepDescriptor.id}' is an unresolved 'uses:' reference - " +
                    "resolve it before import."
            )

        // Step ID: explicit UUID, deterministic v5, or fallback random (precomputed for consistency)
        val stepId = resolveStepId(step)

        // Environment ID: lookup table first (human-readable keys),
        // then direct UUID parse (canonical UUID keys from exporter),
        // then fallback (error case, but don't fail here)
        val envUuid = environmentKeyToUuid[step.environmentId]
            ?: tryParseUuid( step.environmentId )
            ?: UUID.randomUUID()

        val inputs = step.inputs.map { PortImporter.importInputPort( it, workflowNamespace ) }
        val outputs = step.outputs.map { PortImporter.importOutputPort( it, workflowNamespace ) }

        return Step(
            metadata = MetadataImporter.importStepMetadata( stepId, step.id, step.metadata ),
            task = TaskImporter.importTask( step.task, workflowNamespace, inputs, outputs ),
            environmentId = envUuid,
            inputs = inputs,
            outputs = outputs,
        )
    }

    private fun resolveStepId(stepDescriptor: StepDescriptor): UUID =
        stepDescriptor.id?.let { tryParseUuid( it ) }
            ?: DeterministicUUID.v5(workflowNamespace, "step:${stepDescriptor.id ?: "unnamed"}")
}
