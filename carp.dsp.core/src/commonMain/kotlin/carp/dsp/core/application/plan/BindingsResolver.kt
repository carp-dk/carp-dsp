package carp.dsp.core.application.plan

import dk.cachet.carp.analytics.application.plan.DataRef
import dk.cachet.carp.analytics.application.plan.PlanIssue
import dk.cachet.carp.analytics.application.plan.PlanIssueSeverity
import dk.cachet.carp.analytics.application.plan.PlannedStep
import dk.cachet.carp.analytics.application.plan.ResolvedBindings
import dk.cachet.carp.analytics.domain.data.StepOutputSource
import dk.cachet.carp.analytics.domain.workflow.Step
import dk.cachet.carp.common.application.UUID

/**
 * BindingsResolver builds a ResolvedBindings instance for a single Step.
 *
 * It resolves:
 * - Logical input sources → concrete DataRef
 * - Declared outputs → deterministic sink DataRef
 *
 */
class BindingsResolver {

    /**
     * Resolves a step's inputs and outputs into concrete ResolvedBindings.
     *
     * @param step The step to resolve bindings for
     * @param plannedSteps Map of already planned steps (stepId -> PlannedStep)
     * @param issues Mutable list to collect any resolution issues
     * @return ResolvedBindings instance (even if errors exist)
     */
    fun resolve(
        step: Step,
        plannedSteps: Map<UUID, PlannedStep>,
        issues: MutableList<PlanIssue>
    ): ResolvedBindings {
        val stepId = step.metadata.id

        // Resolve outputs - create deterministic DataRefs
        val outputs = resolveOutputs(step)

        // Resolve inputs - switch on input source type
        val inputs = resolveInputs(step, plannedSteps, issues, stepId)

        return ResolvedBindings(inputs, outputs)
    }

    /**
     * Resolves step outputs into deterministic DataRefs.
     * For each OutputDataSpec, creates a DataRef using the output.id as identifier.
     */
    private fun resolveOutputs(
        step: Step
    ): Map<UUID, DataRef> {
        return step.outputs.associate { output ->
            val dataRef = DataRef(
                id = output.id,
                type = output.format?.toString() ?: "unknown"
            )
            output.id to dataRef
        }
    }

    /**
     * Resolves step inputs based on their source type.
     * P0 only supports StepOutputSource - all others emit errors.
     */
    private fun resolveInputs(
        step: Step,
        plannedSteps: Map<UUID, PlannedStep>,
        issues: MutableList<PlanIssue>,
        stepId: UUID
    ): Map<UUID, DataRef> {
        val inputs = mutableMapOf<UUID, DataRef>()

        for (input in step.inputs) {
            when (val source = input.source) {
                is StepOutputSource -> {
                    val producerOutputRef = resolveStepOutputSource(
                        source,
                        plannedSteps,
                        issues,
                        stepId,
                        input.id
                    )
                    if (producerOutputRef != null) {
                        inputs[input.id] = producerOutputRef
                    }
                    // If null, error was already emitted in resolveStepOutputSource
                }

                else -> {
                    // P0: All other sources are unsupported
                    issues.add(
                        PlanIssue(
                            severity = PlanIssueSeverity.ERROR,
                            code = "UNSUPPORTED_INPUT_SOURCE",
                            message = "Input source type '${source::class.simpleName}' is not supported in P0. " +
                                    "Only StepOutputSource is supported.",
                            stepId = stepId
                        )
                    )
                }
            }
        }

        return inputs.toMap()
    }

    /**
     * Resolves a StepOutputSource to the concrete DataRef from the producer step.
     */
    private fun resolveStepOutputSource(
        source: StepOutputSource,
        plannedSteps: Map<UUID, PlannedStep>,
        issues: MutableList<PlanIssue>,
        consumerStepId: UUID,
        inputId: UUID
    ): DataRef? {
        // Find producer step
        val producer = plannedSteps[source.stepId]
        if (producer == null) {
            issues.add(
                PlanIssue(
                    severity = PlanIssueSeverity.ERROR,
                    code = "MISSING_PRODUCER_STEP",
                    message = "Producer step '${source.stepId}' not found for input '$inputId'. " +
                            "Step may not exist or may not have been planned yet.",
                    stepId = consumerStepId
                )
            )
            return null
        }

        // Find producer output
        val producerOutputRef = producer.bindings.output(source.outputId)
        if (producerOutputRef == null) {
            issues.add(
                PlanIssue(
                    severity = PlanIssueSeverity.ERROR,
                    code = "MISSING_PRODUCER_OUTPUT",
                    message = "Output '${source.outputId}' not found in producer step '${source.stepId}' " +
                            "for input '$inputId'. Available outputs: " +
                            "${producer.bindings.outputs.keys.map { it.toString() }.sorted()}.",
                    stepId = consumerStepId
                )
            )
            return null
        }

        return producerOutputRef
    }
}
