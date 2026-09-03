package carp.dsp.steps.sql

private const val LINE_COMMENT = "--"
private const val BLOCK_OPEN = "/*"
private const val BLOCK_CLOSE = "*/"

private val READS = Regex("""^\s*(select|with)\b""", RegexOption.IGNORE_CASE)

/**
 * Returns [sql] with comments and the contents of quoted literals replaced by
 * spaces, leaving the structure readable without the text inside it.
 *
 * Dollar-quoted strings are rejected: a `;` inside one is read as a
 * separator, so such a statement is refused rather than run.
 */
@Suppress("CyclomaticComplexMethod")
internal fun maskLiterals(sql: String): String
{
    val masked = StringBuilder(sql.length)
    var index = 0
    var blockDepth = 0
    var inLineComment = false
    var quote: Char? = null

    while (index < sql.length)
    {
        val char = sql[index]
        val pair = sql.substring(index, minOf(index + 2, sql.length))

        when
        {
            inLineComment ->
            {
                inLineComment = char != '\n'
                masked.append(if (inLineComment) ' ' else char)
                index++
            }

            blockDepth > 0 -> when (pair)
            {
                // Block comments nest, so a `/*` inside one has to be counted.
                BLOCK_OPEN -> { blockDepth++; masked.append("  "); index += 2 }
                BLOCK_CLOSE -> { blockDepth--; masked.append("  "); index += 2 }
                else -> { masked.append(if (char == '\n') char else ' '); index++ }
            }

            quote != null ->
            {
                val closing = char == quote
                if (closing) quote = null
                masked.append(if (closing) char else ' ')
                index++
            }

            pair == LINE_COMMENT -> { inLineComment = true; masked.append("  "); index += 2 }
            pair == BLOCK_OPEN -> { blockDepth = 1; masked.append("  "); index += 2 }
            char == '\'' || char == '"' -> { quote = char; masked.append(char); index++ }
            else -> { masked.append(char); index++ }
        }
    }

    return masked.toString()
}

/**
 * Checks that [sql] is one statement that only reads.
 *
 * Comments and quoted text are ignored, so a `;` or a keyword inside a literal
 * does not count. This rejects mistakes, not malicious attacks.
 *
 * @throws IllegalArgumentException when [sql] is empty once comments are
 *   removed, does not begin with `SELECT` or `WITH`, or holds more than one
 *   statement.
 */
fun requireSingleReadStatement(sql: String)
{
    val masked = maskLiterals(sql)

    require(masked.isNotBlank()) { "The statement is empty once its comments are removed." }

    require(READS.containsMatchIn(masked))
    {
        val found = masked.trim().takeWhile { !it.isWhitespace() }
        "This step only runs statements that read: expected SELECT or WITH, found '$found'."
    }

    require(masked.trimEnd().trimEnd(';').none { it == ';' })
    {
        "Only one statement can be run; remove the ';' and everything after it."
    }
}
