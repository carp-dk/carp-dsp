@file:Suppress("PackageDirectoryMismatch")

package carp.dsp.steps.sql

/** A statement whose `:name` placeholders have become `?`, and the names in order. */
data class BoundStatement(val sql: String, val names: List<String>)

private fun isNameStart(char: Char): Boolean = char.isLetter() || char == '_'

private fun isNamePart(char: Char): Boolean = char.isLetterOrDigit() || char == '_'

/**
 * Replaces every `:name` in [sql] with `?`, and returns the names in the order
 * their placeholders appear.
 *
 * A `:` inside a comment or a quoted literal is text, `::` is a cast, and `:`
 * before anything but a letter or underscore - an array slice, say - is left
 * alone. A name that appears twice yields two entries, so its value is bound
 * once per placeholder.
 */
fun bindNames(sql: String): BoundStatement
{
    val masked = maskLiterals(sql)

    val translated = StringBuilder(sql.length)
    val names = mutableListOf<String>()
    var index = 0

    while (index < sql.length)
    {
        val name = nameAt(masked, index)
        if (name == null)
        {
            translated.append(sql[index])
            index++
        }
        else
        {
            names += name
            translated.append('?')
            index += name.length + 1
        }
    }

    return BoundStatement(translated.toString(), names)
}

/** The placeholder name starting at [index], or `null` when there is none. */
private fun nameAt(masked: String, index: Int): String?
{
    if (masked[index] != ':') return null

    // The second colon of a cast, which the first colon already declined.
    if (index > 0 && masked[index - 1] == ':') return null

    val next = masked.getOrNull(index + 1) ?: return null
    if (next == ':' || !isNameStart(next)) return null

    var end = index + 1
    while (end < masked.length && isNamePart(masked[end])) end++

    return masked.substring(index + 1, end)
}
