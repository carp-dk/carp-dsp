package carp.dsp.detekt

import carp.dsp.detekt.rules.CurlyBracesOnSeparateLine
import carp.dsp.detekt.rules.SpacingInParentheses
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider


class CarpRuleSetProvider : RuleSetProvider
{
    override val ruleSetId: String = "carp"

    override fun instance( config: Config ): RuleSet =
        RuleSet(
            ruleSetId,
            listOf(
                SpacingInParentheses( config ),
                CurlyBracesOnSeparateLine( config )
            )
        )
}
