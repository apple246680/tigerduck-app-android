package org.ntust.app.tigerduck.data.model

/**
 * A colour or a name set on the class table that the backend has not
 * confirmed, kept in `pending_course_overrides.json` and sent again on the
 * next sync; see `PendingCourseOverrides`. One row is one change: the
 * course's colour, or its name in one locale.
 *
 * Every field is nullable or primitive: Gson builds this class without its
 * constructor (see CLAUDE.md), so a field added later reads back from an
 * older file as null or zero rather than crashing.
 */
data class PendingCourseOverride(
    /** The override PATCH's path segment: the course's Moodle idnumber, which the backend keys the course by. */
    val moodleId: String?,
    /** The colour set, `#RRGGBB`; null on a row that carries a name. */
    val colorHex: String?,
    /** The name set in [locale], or "" for one cleared; null on a row that carries a colour. */
    val customName: String?,
    /** The locale [customName] is for. */
    val locale: String?,
    /** When the user made the change; a later change of the same [key] replaces it. */
    val changedAtMs: Long,
) {
    /**
     * What the row changes — the course's colour, or its name in one locale —
     * or null for a row too damaged to send.
     */
    val key: String?
        get() {
            val id = moodleId?.takeIf { it.isNotBlank() } ?: return null
            return when {
                colorHex != null -> colorKey(id)
                customName != null && locale != null -> nameKey(id, locale)
                else -> null
            }
        }

    companion object {
        /** The key of the colour of the course the backend knows as [moodleId]. */
        fun colorKey(moodleId: String): String = "$moodleId:color"

        /** The key of that course's name in [locale]. */
        fun nameKey(moodleId: String, locale: String): String = "$moodleId:name:$locale"
    }
}
