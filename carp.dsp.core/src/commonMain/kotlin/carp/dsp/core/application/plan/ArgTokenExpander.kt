package carp.dsp.core.application.plan

import dk.cachet.carp.analytics.application.plan.ExpandedArg
import dk.cachet.carp.analytics.application.plan.PlanIssue
import dk.cachet.carp.analytics.application.plan.PlanIssueSeverity
import dk.cachet.carp.analytics.application.plan.ResolvedBindings
import dk.cachet.carp.analytics.domain.tasks.ArgToken
import dk.cachet.carp.analytics.domain.tasks.InputPathSubstitutionRef
import dk.cachet.carp.analytics.domain.tasks.InputRef
import dk.cachet.carp.analytics.domain.tasks.Literal
import dk.cachet.carp.analytics.domain.tasks.OutputPathSubstitutionRef
import dk.cachet.carp.analytics.domain.tasks.OutputRef
import dk.cachet.carp.analytics.domain.tasks.ParamRef
import dk.cachet.carp.common.application.UUID

/**
 * Expands argument tokens to semantic expanded arguments.
 *
 * Converts abstract ArgTokens into ExpandedArg objects that carry information
 * about what runtime resolution they need:
 *
 * Expansion rules:
 * 1. Literal → ExpandedArg.Literal (no resolution needed)
 * 2. InputRef → ExpandedArg.DataReference (needs path resolution)
 * 3. OutputRef → ExpandedArg.DataReference (needs path resolution)
 * 4. InputPathSubstitutionRef → ExpandedArg.PathSubstitution (needs path substitution)
 * 5. OutputPathSubstitutionRef → ExpandedArg.PathSubstitution (needs path substitution)
 * 6. ParamRef → Warning, not yet implemented
 *
 * Examples:
 * - "preprocess.py" → Literal("preprocess.py")
 * - "input.raw-eeg" → DataReference(uuid=550e8400-...)
 * - "--input=$(input.raw-eeg)" → PathSubstitution(uuid=550e8400-..., template="--input=$()")
 * - "--model=$(env.MODEL_PATH)" → Literal("--model=$(env.MODEL_PATH)")
 */
class ArgTokenExpander {

    /**
     * Expands a list of argument tokens to semantic expanded arguments.
     *
     * @param tokens List of ArgToken to expand
     * @param bindings Resolved bindings for input/output references
     * @param issues Mutable list to collect errors and warnings
     * @param stepId Optional step ID for error reporting
     * @return List of ExpandedArg objects
     */
    fun expand(
        tokens: List<ArgToken>,
        bindings: ResolvedBindings,
        issues: MutableList<PlanIssue>,
        stepId: UUID? = null
    ): List<ExpandedArg> {
        return tokens.mapNotNull { token ->
            expandToken(token, bindings, issues, stepId)
        }
    }

    /**
     * Expands a single argument token to its corresponding ExpandedArg.
     */
    private fun expandToken(
        token: ArgToken,
        bindings: ResolvedBindings,
        issues: MutableList<PlanIssue>,
        stepId: UUID?
    ): ExpandedArg? {
        return when (token) {
            is Literal -> ExpandedArg.Literal(token.value)
            is InputRef -> expandInputRef(token, bindings, issues, stepId)
            is OutputRef -> expandOutputRef(token, bindings, issues, stepId)
            is InputPathSubstitutionRef -> ExpandedArg.PathSubstitution(token.inputId, token.template)
            is OutputPathSubstitutionRef -> ExpandedArg.PathSubstitution(token.outputId, token.template)
            is ParamRef -> expandParamRef(token, issues, stepId)
        }
    }

    private fun expandInputRef(
        token: InputRef,
        bindings: ResolvedBindings,
        issues: MutableList<PlanIssue>,
        stepId: UUID?
    ): ExpandedArg? {
        val ref = bindings.input(token.inputId)
        if (ref == null) {
            issues += PlanIssue(
                severity = PlanIssueSeverity.ERROR,
                code = "MISSING_INPUT_BINDING",
                message = "Missing input binding '${token.inputId}'.",
                stepId = stepId
            )
            return null
        }
        return ExpandedArg.DataReference(dataRefId = ref.id)
    }

    private fun expandOutputRef(
        token: OutputRef,
        bindings: ResolvedBindings,
        issues: MutableList<PlanIssue>,
        stepId: UUID?
    ): ExpandedArg? {
        val ref = bindings.output(token.outputId)
        if (ref == null) {
            issues += PlanIssue(
                severity = PlanIssueSeverity.ERROR,
                code = "MISSING_OUTPUT_BINDING",
                message = "Missing output binding '${token.outputId}'.",
                stepId = stepId
            )
            return null
        }
        return ExpandedArg.DataReference(dataRefId = ref.id)
    }

    private fun expandParamRef(
        token: ParamRef,
        issues: MutableList<PlanIssue>,
        stepId: UUID?
    ): ExpandedArg {
        issues += PlanIssue(
            severity = PlanIssueSeverity.WARNING,
            code = "PARAM_REF_NOT_IMPLEMENTED",
            message = "Parameter reference '${token.name}' is not yet fully implemented.",
            stepId = stepId
        )
        return ExpandedArg.Literal("\${${token.name}}")
    }
}
