package carp.dsp.core.application.plan

import dk.cachet.carp.analytics.application.plan.*
import dk.cachet.carp.analytics.domain.data.FileLocation
import dk.cachet.carp.analytics.domain.data.InputDataSpec
import dk.cachet.carp.analytics.domain.workflow.Step
import dk.cachet.carp.common.application.UUID

/**
 * BindingsResolver builds a ResolvedBindings instance for a single Step.
 *
 * It resolves:
 * - Logical input sources → concrete ResolvedInput with resolved DataLocations
 * - Declared outputs → ResolvedOutput with resolved DataLocations
 *
 * **Design:** This resolver is now **fully extensible**. It delegates all path/location
 * resolution logic to the DataLocation implementations themselves (Strategy Pattern).
 * Adding new DataLocation types requires **no changes** to this resolver.
 */
class BindingsResolver
{
    /**
     * Context object for resolving inputs.
     */
    private data class ResolutionContext(
        val step: Step,
        val stepId: UUID,
        val stepName: String,
        val plannedSteps: Map<UUID, PlannedStep>,
        val executionIndex: Int,
        val issues: MutableList<PlanIssue>,
        val stepsByDescriptorId: Map<String, PlannedStep>
    )

    /**
     * Resolves a step's inputs and outputs into concrete ResolvedBindings.
     *
     * @param step The step to resolve bindings for
     * @param plannedSteps Map of already planned steps (stepId -> PlannedStep)
     * @param issues Mutable list to collect any resolution issues
     * @param executionIndex Base workspace directory for path resolution
     * @return ResolvedBindings instance (even if errors exist)
     */
    fun resolve(
        step: Step,
        plannedSteps: Map<UUID, PlannedStep>,
        issues: MutableList<PlanIssue>,
        executionIndex: Int
    ): ResolvedBindings
    {
        val stepId = step.metadata.id
        val stepName = step.metadata.name
        val stepsByDescriptorId: Map<String, PlannedStep> = plannedSteps.values
            .mapNotNull { ps -> ps.metadata.descriptorId?.let { it to ps } }
            .toMap()

        // Resolve outputs - delegate to each location's resolve() method
        val outputs = resolveOutputs( step, executionIndex, stepName )

        // Resolve inputs - handle both external and step-based inputs
        val context = ResolutionContext(
            step = step,
            stepId = stepId,
            stepName = stepName,
            plannedSteps = plannedSteps,
            executionIndex = executionIndex,
            issues = issues,
            stepsByDescriptorId = stepsByDescriptorId
        )
        val inputs = resolveInputs( context )

        return ResolvedBindings( inputs, outputs )
    }

    /**
     * Resolves step outputs into ResolvedOutput objects.
     *
     */
    private fun resolveOutputs(
        step: Step,
        executionIndex: Int,
        stepName: String
    ): Map<UUID, ResolvedOutput>
    {
        return step.outputs.associate { output ->
            val resolvedLocation = output.location.resolve(
                executionIndex = executionIndex,
                stepName = stepName,
                outputName = output.name
            )

            val resolvedOutput = ResolvedOutput(
                spec = output,
                location = resolvedLocation
            )

            output.id to resolvedOutput
        }
    }

    /**
     * Resolves step inputs based on their origin (external or step-based).
     *
     * **External inputs** (stepRef == null): Use location as-is (or resolve if needed)
     * **Step-based inputs** (stepRef != null): Get location from producer step's output
     */
    private fun resolveInputs(
        context: ResolutionContext
    ): Map<UUID, ResolvedInput>
    {
        return context.step.inputs.associate { input ->
            input.id to when
            {
                input.stepRef == null -> resolveExternalInput(input, context)
                else -> resolveStepBasedInput(input, context)
            }
        }
    }

    /**
     * Resolves an external data source input.
     */
    private fun resolveExternalInput(
        input: InputDataSpec,
        context: ResolutionContext
    ): ResolvedInput {
        // Warn on paths that are clearly system-specific
        if (input.location is FileLocation) {
            val path = (input.location as FileLocation).path
            if (isSystemSpecificPath(path)) {
                context.issues.add(
                    PlanIssue(
                        severity = PlanIssueSeverity.WARNING,
                        code = "SYSTEM_SPECIFIC_PATH",
                        message = "Input '${input.name}' has a system-specific path '$path'. " +
                                "This plan may not be portable across machines.",
                        stepId = context.stepId
                    )
                )
            }
        }
        // Use location as-is — no resolve() call
        return ResolvedInput(spec = input, location = input.location)
    }

    private fun isSystemSpecificPath(path: String): Boolean {
        return path.matches(Regex("^[A-Za-z]:.*")) || // Windows drive letter
                path.startsWith("\\\\") || // UNC network path
                path.startsWith("/home/") || // Unix user home
                path.startsWith("/Users/") // Mac user home
    }

    /**
     * Resolves a step-based input by finding the producer step's output.
     */
    private fun resolveStepBasedInput(
        input: InputDataSpec,
        context: ResolutionContext
    ): ResolvedInput
    {
        // Find producer step by name
        val producer = context.plannedSteps.values.firstOrNull { it.metadata.name == input.stepRef }
            ?: context.stepsByDescriptorId[input.stepRef]

        if ( producer == null )
        {
            context.issues.add(
                PlanIssue(
                    severity = PlanIssueSeverity.ERROR,
                    code = "MISSING_PRODUCER_STEP",
                    message = "Producer step '${input.stepRef}' not found in planned steps.",
                    stepId = context.stepId
                )
            )
            return resolveExternalInput(input, context)
        }

        // Get producer's output with matching name
        val producerOutput = producer.bindings.outputs.values.firstOrNull { output ->
            output.spec.name == input.name
        }

        if ( producerOutput == null )
        {
            context.issues.add(
                PlanIssue(
                    severity = PlanIssueSeverity.ERROR,
                    code = "MISSING_PRODUCER_OUTPUT",
                    message = "Producer step '${input.stepRef}' does not have output named '${input.name}'.",
                    stepId = context.stepId
                )
            )
            return resolveExternalInput(input, context)
        }

        return ResolvedInput(
            spec = input,
            location = producerOutput.location
        )
    }
}
