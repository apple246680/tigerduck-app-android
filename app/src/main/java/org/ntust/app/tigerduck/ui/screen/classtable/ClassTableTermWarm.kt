// Filling the cache of every term the picker offers, once, so a term picked
// later shows at once instead of an empty grid until its fetch returns
// (tigerduck-app-android#265). The iPhone's `warmAllSemesterCaches` fans the
// terms out together; here they go one at a time, since nobody is waiting on
// them and the school servers see every lookup.

package org.ntust.app.tigerduck.ui.screen.classtable

internal class ClassTableTermWarm(
    /** The picker's terms, newest first. */
    private val terms: () -> List<String>,
    /** Whether a term has courses cached. One that has is not fetched again. */
    private val isCached: suspend (String) -> Boolean,
    /** Whether a term may be fetched right now: signed in, online, not the demo account. */
    private val mayFetch: () -> Boolean,
    /** The term on screen, whose own fetch handles it. */
    private val shownTerm: () -> String,
) {
    private var started = false

    /**
     * Fetches each term with no cache, newest first and one at a time,
     * through [fetch], which says whether the school answered. The first term
     * it did not answer for ends the run: the rest would fail the same way,
     * and each failed lookup is a request the server still takes. Each term
     * is checked again as its turn comes, so one picked or cached while an
     * earlier term ran is left to its own fetch.
     *
     * Once per sign-in: a later call returns at once, until [reset].
     */
    suspend fun run(fetch: suspend (String) -> Boolean) {
        if (started) return
        started = true
        for (term in terms()) {
            if (!mayFetch()) return
            if (term == shownTerm() || isCached(term)) continue
            if (!fetch(term)) return
        }
    }

    /** A sign-out: the next account's first fetch warms its terms. */
    fun reset() {
        started = false
    }
}
