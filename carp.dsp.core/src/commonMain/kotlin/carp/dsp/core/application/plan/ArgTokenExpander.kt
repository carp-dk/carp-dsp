package carp.dsp.core.application.plan

import dk.cachet.carp.analytics.application.plan.PlanIssue
import dk.cachet.carp.analytics.application.plan.PlanIssueSeverity
import dk.cachet.carp.analytics.application.plan.ResolvedBindings
import dk.cachet.carp.analytics.domain.tasks.ArgToken
import dk.cachet.carp.analytics.domain.tasks.InputRef
import dk.cachet.carp.analytics.domain.tasks.Literal
import dk.cachet.carp.analytics.domain.tasks.OutputRef
import dk.cachet.carp.analytics.domain.tasks.ParamRef
import dk.cachet.carp.common.application.UUID

class ArgTokenExpander {

    fun expand(
        tokens: List<ArgToken>,
        bindings: ResolvedBindings,
        issues: MutableList<PlanIssue>,
        stepId: UUID? = null
    ): List<String> {
        return tokens.mapNotNull { token ->
            when (token) {
                is Literal -> token.value

                is InputRef -> {
                    val ref = bindings.input(token.inputId)
                    if (ref == null) {
                        issues += PlanIssue(
                            severity = PlanIssueSeverity.ERROR,
                            code = "MISSING_INPUT_BINDING",
                            message = "Missing input binding '${token.inputId}'.",
                            stepId = stepId
                        )
                        null
                    } else {
                        ref.id.toString()
                    }
                }

                is OutputRef -> {
                    val ref = bindings.output(token.outputId)
                    if (ref == null) {
                        issues += PlanIssue(
                            severity = PlanIssueSeverity.ERROR,
                            code = "MISSING_OUTPUT_BINDING",
                            message = "Missing output binding '${token.outputId}'.",
                            stepId = stepId
                        )
                        null
                    } else {
                        ref.id.toString()
                    }
                }

                is ParamRef -> {
                    // For now, ParamRef is not fully implemented
                    // Return the parameter name as a placeholder
                    // TODO: Implement parameter resolution from step configuration
                    issues += PlanIssue(
                        severity = PlanIssueSeverity.WARNING,
                        code = "PARAM_REF_NOT_IMPLEMENTED",
                        message = "Parameter reference '${token.name}' is not yet fully implemented.",
                        stepId = stepId
                    )
                    "\${${token.name}}"
                }
            }
        }
    }
}
