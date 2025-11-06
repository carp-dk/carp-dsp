package carp.dsp.detekt

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace


/**
 * Verifies whether the entire [element] is defined on one line.
 */
fun isDefinedOnOneLine( element: PsiElement ): Boolean =
    !element.textContains( '\n' )

/**
 * Determines whether the given [element] starts on a new line, without anything other than whitespace preceding it.
 */
fun startsOnNewLine( element: PsiElement ): Boolean
{
    // In case there is no preceding element, it has to start on a new line.
    val before = getPrecedingElement( element ) ?: return true

    return before is PsiWhiteSpace && before.text.contains( "\n" )
}

/**
 * Verifies whether [element1] and [element2] are on separate lines and have the same indentation.
 *
 * Tabs are converted to spaces (4 spaces per tab) for alignment comparison.
 */
fun areAligned( element1: PsiElement, element2: PsiElement ): Boolean
{
    val node1Indent = getIndentSize( element1 )
    val node2Indent = getIndentSize( element2 )

    return node1Indent == node2Indent
}

/**
 * Gets the position of the element on the current line.
 *
 * Tabs are converted to spaces (4 spaces per tab) for indent calculation.
 */
fun getIndentSize( element: PsiElement ): Int
{
    var indentSize = 0
    var curElement = element
    var foundNewline = false
    while ( !foundNewline )
    {
        // Look at preceding element, or early out in case no more elements.
        val preceding = getPrecedingElement( curElement ) ?: break

        if ( preceding is PsiWhiteSpace && preceding.text.contains( '\n' ) )
        {
            val whitespace = preceding.text
            val lastNewline = whitespace.lastIndexOf( '\n' )

            // When a newline is found, count all characters from the newline, converting tabs to spaces.
            foundNewline = true
            val indentString = whitespace.substring( lastNewline + 1 )
            indentSize += calculateIndentSize( indentString )
        }
        else
        {
            indentSize += preceding.textLength
        }
        curElement = preceding
    }

    return indentSize
}

/**
 * Calculates the indent size of a string, converting tabs to spaces (4 spaces per tab).
 */
private fun calculateIndentSize( indentString: String, tabSize: Int = 4 ): Int
{
    var size = 0
    for ( char in indentString )
    {
        size += when ( char )
        {
            '\t' -> tabSize
            else -> 1
        }
    }
    return size
}

/**
 * Get the element preceding the given [element], regardless of whether it is owned by a different parent.
 *
 * @return The preceding element, or null when there is no preceding element.
 */
fun getPrecedingElement( element: PsiElement ): PsiElement?
{
    // If preceding element is part of the same parent, no need to start traversing parent.
    if ( element.prevSibling != null ) return element.prevSibling

    // If no preceding element exists, search in the parent if available.
    if ( element.parent != null )
    {
        return getPrecedingElement( element.parent )
    }

    // No preceding element and no parent with preceding elements. This must be the first element.
    return null
}

/**
 * Get the element succeeding the given [element], regardless of whether it is owned by a different parent.
 *
 * @return The next element, or null when there is no next element.
 */
fun getNextElement( element: PsiElement ): PsiElement?
{
    // If next element is part of the same parent, no need to start traversing parent.
    if ( element.nextSibling != null ) return element.nextSibling

    // If no next element exists, search in the parent if available.
    if ( element.parent != null )
    {
        return getNextElement( element.parent )
    }

    // No next element and no parent with next elements. This must be the last element.
    return null
}
