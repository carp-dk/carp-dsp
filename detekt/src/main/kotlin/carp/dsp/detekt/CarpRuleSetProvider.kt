package carp.dsp.detekt

import carp.dsp.detekt.rules.CurlyBracesOnSeparateLine
import carp.dsp.detekt.rules.SpacingInParentheses
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

/**
 * Custom Detekt rule set provider for CARP DSP coding conventions.
 */
class CarpRuleSetProvider : RuleSetProvider {
    override val ruleSetId: String = "carp"

    override fun instance(config: Config): RuleSet {
        return RuleSet(
            ruleSetId,
            listOf(
                CurlyBracesOnSeparateLine(config),
                SpacingInParentheses(config)
            )
        )
    }
}

