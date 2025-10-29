package carp.dsp.detekt.rules

import io.gitlab.arturbosch.detekt.api.*
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.*

/**
 * Reports curly braces of multi-line blocks that are not placed on separate lines.
 *
 * CARP coding convention: Curly braces of multi-line blocks need to be placed on separate lines
 * (with the exception of trailing lambda arguments), aligned with the start of the definition
 * the block is associated with (e.g., class, function, object literal, if, or return).
 */
class CurlyBracesOnSeparateLine(config: Config = Config.empty) : Rule(config) {

    override val issue = Issue(
        javaClass.simpleName,
        Severity.Style,
        "Curly braces of multi-line blocks should be placed on separate lines",
        Debt.FIVE_MINS
    )

    override fun visitBlockExpression(expression: KtBlockExpression) {
        super.visitBlockExpression(expression)

        // Skip single-line blocks
        if (isSingleLine(expression)) return

        // Skip trailing lambda arguments (allowed exception)
        if (isTrailingLambda(expression)) return

        val lBrace = expression.lBrace ?: return
        val rBrace = expression.rBrace ?: return

        // Check if opening brace is on its own line or shares line with code before it
        val prevSibling = lBrace.prevSibling
        if (prevSibling != null && !endsWithNewline(prevSibling)) {
            report(CodeSmell(
                issue,
                Entity.from(lBrace),
                "Opening curly brace of multi-line block should be on a separate line"
            ))
        }

        // Check if closing brace is on its own line
        val nextSibling = rBrace.nextSibling
        if (nextSibling != null && !startsWithNewline(rBrace)) {
            report(CodeSmell(
                issue,
                Entity.from(rBrace),
                "Closing curly brace of multi-line block should be on a separate line"
            ))
        }
    }

    private fun isSingleLine(expression: KtBlockExpression): Boolean {
        val text = expression.text
        return !text.contains('\n')
    }

    private fun isTrailingLambda(expression: KtBlockExpression): Boolean {
        val parent = expression.parent
        return parent is KtLambdaExpression &&
               parent.parent is KtLambdaArgument
    }

    private fun endsWithNewline(element: PsiElement): Boolean {
        val text = element.text
        return text.endsWith('\n') || text.endsWith("\r\n")
    }

    private fun startsWithNewline(element: PsiElement): Boolean {
        val prevSibling = element.prevSibling ?: return true
        val text = prevSibling.text
        return text.endsWith('\n') || text.endsWith("\r\n")
    }
}

