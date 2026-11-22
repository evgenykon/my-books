package io.github.evgenykon.mybooks.ui

/**
 * Character range of a saved quote inside the block text.
 *
 * Quotes are stored as plain text and may have been captured across lines, where the reader
 * joined the pieces with a space, so the match ignores repeated whitespace. Returns null when
 * the text has changed too much to find the quote.
 */
internal fun quoteRangeIn(blockText: String, quote: String): IntRange? {
    if (quote.isBlank() || blockText.isEmpty()) return null

    val direct = blockText.indexOf(quote)
    if (direct >= 0) return direct until (direct + quote.length)

    // Whitespace-insensitive pass: build a normalized copy and remember original positions.
    val normalized = StringBuilder()
    val positions = ArrayList<Int>(blockText.length)
    var previousSpace = true
    for (i in blockText.indices) {
        val ch = blockText[i]
        if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
            if (!previousSpace) {
                normalized.append(' ')
                positions.add(i)
                previousSpace = true
            }
        } else {
            normalized.append(ch)
            positions.add(i)
            previousSpace = false
        }
    }

    val needle = quote.trim().replace(Regex("[ \\t\\r\\n]+"), " ")
    val at = normalized.indexOf(needle)
    if (at < 0) return null
    val from = positions[at]
    val to = positions[(at + needle.length - 1).coerceAtMost(positions.lastIndex)]
    return from..to
}
