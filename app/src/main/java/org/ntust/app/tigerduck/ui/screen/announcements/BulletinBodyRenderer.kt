package org.ntust.app.tigerduck.ui.screen.announcements

import org.ntust.app.tigerduck.network.model.BulletinDetail

/**
 * What the bulletin detail renders as its body. Ports the iPhone's `BulletinBodyRenderer.swift`
 * so both apps pick the same field and make the same fix-ups to the server's markdown.
 */
internal object BulletinBodyRenderer {

    /**
     * The first non-blank of `body_clean` (the LLM's cleaned markdown, every fact kept), `body_md`
     * (the raw scrape), the detail's summary, and [fallbackSummary] (the list row's); trimmed, or
     * empty when there is none.
     */
    fun bodyMarkdown(detail: BulletinDetail, fallbackSummary: String? = null): String =
        sequenceOf(detail.bodyClean, detail.bodyMd, detail.summary, fallbackSummary)
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull()
            .orEmpty()

    /**
     * Rewrites the shapes the CommonMark parser leaves as raw text. The LLM writes `*   ` and `+ `
     * list markers, and the school puts `**` flush against full-width punctuation, where the
     * closing run fails the flanking rules and both `**` show. A space between the run and the
     * punctuation is what lets the bold through.
     *
     * The list-marker pattern stays within a line (`[ \t]`, where the iPhone's has `\s`), so a
     * bare `*` cannot pull the line below it up into a list item.
     */
    fun normalize(source: String): String = source
        .replace(LIST_MARKER, "$1- ")
        .replace(BOLD_BEFORE_PUNCTUATION, "**$1** $2")
        .replace(BOLD_ENDING_IN_PUNCTUATION, "**$1** $2")
        .replace(BOLD_AFTER_OPENING_BRACKET, "$1 **$2**")

    private val LIST_MARKER = Regex("""(?m)^([ \t]*)[*+][ \t]+""")
    private val BOLD_BEFORE_PUNCTUATION = Regex("""\*\*([^*\n]+)\*\*([、。，．：；！？」』）])""")
    private val BOLD_ENDING_IN_PUNCTUATION = Regex("""\*\*([^*\n]*?[、。，．：；！？])\*\*(\S)""")
    private val BOLD_AFTER_OPENING_BRACKET = Regex("""([「『（])\*\*([^*\n]+)\*\*""")
}
