package carp.dsp.detekt.rules

import io.gitlab.arturbosch.detekt.api.*
import org.jetbrains.kotlin.psi.*

/**
 * Reports missing spaces in parentheses.
 *
 * CARP coding convention: Spaces need to be added in all parentheses,
 * except for those of higher-order functions.
 *
 * Examples:
 * - Correct: `if ( true )`, `fun test( a: Int )`
 * - Correct (higher-order): `val f: (Int, Int) -> Int`
 * - Incorrect: `if (true)`, `fun test(a: Int)`
 */
class SpacingInParentheses(config: Config = Config.empty) : Rule(config) {

    override val issue = Issue(
        javaClass.simpleName,
        Severity.Style,
        "Spaces should be added in parentheses (except for higher-order functions)",
        Debt.FIVE_MINS
    )

    override fun visitParameterList(list: KtParameterList) {
        super.visitParameterList(list)

        // Skip if this is a function type (higher-order function)
        if (isFunctionType(list)) return

        checkSpacing(list)
    }

    override fun visitValueArgumentList(list: KtValueArgumentList) {
        super.visitValueArgumentList(list)
        checkSpacing(list)
    }

    override fun visitCondition(condition: KtContainerNodeForControlStructureBody) {
        super.visitCondition(condition)
        // Check if/when/while conditions
        val parent = condition.parent
        if (parent is KtIfExpression || parent is KtWhileExpression || parent is KtWhenExpression) {
            checkParenthesesSpacing(parent)
        }
    }

    private fun isFunctionType(list: KtParameterList): Boolean {
        val parent = list.parent
        return parent is KtFunctionType
    }

    private fun checkSpacing(element: KtElement) {
        val text = element.text
        if (text.isEmpty()) return

        // Check for opening parenthesis without following space
        if (text.startsWith("(") && text.length > 1 && text[1] != ' ' && text[1] != ')') {
            report(CodeSmell(
                issue,
                Entity.from(element),
                "Missing space after opening parenthesis"
            ))
        }

        // Check for closing parenthesis without preceding space
        if (text.endsWith(")") && text.length > 1 && text[text.length - 2] != ' ' && text[text.length - 2] != '(') {
            report(CodeSmell(
                issue,
                Entity.from(element),
                "Missing space before closing parenthesis"
            ))
        }
    }

    private fun checkParenthesesSpacing(element: KtExpression) {
        val text = element.text
        val leftParen = text.indexOf('(')
        val rightParen = text.lastIndexOf(')')

        if (leftParen >= 0 && leftParen < text.length - 1) {
            if (text[leftParen + 1] != ' ' && text[leftParen + 1] != ')') {
                report(CodeSmell(
                    issue,
                    Entity.from(element),
                    "Missing space after opening parenthesis"
                ))
            }
        }

        if (rightParen > 0) {
            if (text[rightParen - 1] != ' ' && text[rightParen - 1] != '(') {
                report(CodeSmell(
                    issue,
                    Entity.from(element),
                    "Missing space before closing parenthesis"
                ))
            }
        }
    }
}

