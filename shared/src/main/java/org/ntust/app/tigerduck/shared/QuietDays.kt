package org.ntust.app.tigerduck.shared

import java.time.LocalDate

/**
 * Days on which no class meets for this user: the school holidays they have
 * not opted back into, resolved on the phone. They reach the watch under
 * [WearProtocol.Schedule.KEY_QUIET_DAYS] as ISO `yyyy-MM-dd` strings.
 */
object QuietDays {

    fun encode(days: Collection<LocalDate>): ArrayList<String> =
        days.sorted().mapTo(ArrayList(days.size)) { it.toString() }

    /**
     * Null, from a phone or a stored snapshot that predates quiet days, reads
     * as none, so the watch shows every class as it did before. An entry that
     * is not a date is dropped rather than failing the rest.
     */
    fun decode(raw: Collection<String>?): Set<LocalDate> =
        raw.orEmpty().mapNotNullTo(HashSet()) { runCatching { LocalDate.parse(it) }.getOrNull() }
}
