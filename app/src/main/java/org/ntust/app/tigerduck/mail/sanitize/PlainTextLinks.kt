package org.ntust.app.tigerduck.mail.sanitize

/** A link in plain text: [start] until [end] (exclusive) of the text, and the [href] a tap opens. */
data class PlainTextLink(val start: Int, val end: Int, val href: String)

/**
 * Finds what a plain-text mail's reader would tap: `http(s)` URLs, `mailto:` links and bare
 * addresses, which become `mailto:` links. What a link opens is never decided here -- the message
 * screen sends every tap through the same check an HTML link gets.
 *
 * A URL runs as far as the characters RFC 3986 allows in one, so it ends at a space, at a quote or
 * angle bracket, and at the first non-ASCII character: Chinese text often runs straight on from a
 * URL with no space in between. Sentence punctuation after it, and a closing bracket it never
 * opened, are left out. The schemes are matched only where no ASCII letter or digit precedes them,
 * checked by hand rather than with `\b`, which Android's regex engine also breaks after a Chinese
 * character and the JVM's does not.
 */
object PlainTextLinks {
    private const val URL_CHARS = """A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%"""
    private val URL = Regex("""(?<![A-Za-z0-9])https?://[$URL_CHARS]+""", RegexOption.IGNORE_CASE)
    private val MAILTO = Regex(
        """(?<![A-Za-z0-9])mailto:[A-Za-z0-9._%+\-]+@[A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)*\.[A-Za-z]{2,}(?:\?[$URL_CHARS]*)?""",
        RegexOption.IGNORE_CASE,
    )
    private val ADDRESS = Regex("""(?<![A-Za-z0-9._%+\-])[A-Za-z0-9._%+\-]+@[A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)*\.[A-Za-z]{2,}(?![A-Za-z0-9\-])""")
    private const val TRAILING = ".,;:!?'*"

    fun find(text: String): List<PlainTextLink> {
        val found = mutableListOf<PlainTextLink>()
        for (pattern in listOf(URL, MAILTO)) {
            for (match in pattern.findAll(text)) {
                val end = match.range.first + trimmed(match.value).length
                // `https://` alone, or one that is all punctuation after it, names nothing.
                if (text.substring(match.range.first, end).substringAfter("://", "x").isEmpty()) continue
                found += PlainTextLink(match.range.first, end, text.substring(match.range.first, end))
            }
        }
        for (match in ADDRESS.findAll(text)) {
            if (found.any { match.range.first < it.end && it.start <= match.range.last }) continue
            found += PlainTextLink(match.range.first, match.range.last + 1, "mailto:${match.value}")
        }
        return found.sortedBy { it.start }
    }

    /** [value] without the sentence punctuation after it, and without a bracket it never opened. */
    private fun trimmed(value: String): String {
        var end = value.length
        while (end > 0) {
            val last = value[end - 1]
            val head = value.substring(0, end)
            end -= when {
                last in TRAILING -> 1
                last == ')' && head.count { it == ')' } > head.count { it == '(' } -> 1
                last == ']' && head.count { it == ']' } > head.count { it == '[' } -> 1
                else -> break
            }
        }
        return value.substring(0, end)
    }
}
