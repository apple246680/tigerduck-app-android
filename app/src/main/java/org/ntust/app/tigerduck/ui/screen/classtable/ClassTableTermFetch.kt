// One term's fetch as far as its list goes: the roster and the hand-added
// rows looked up, and the answers folded onto the term's list as it is once
// they land. The lookups take seconds, and the user keeps editing meanwhile —
// a colour, a course added or deleted — so the list read before them would
// write those edits over; it is read again after.

package org.ntust.app.tigerduck.ui.screen.classtable

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.ntust.app.tigerduck.network.model.MoodleEnrolledCourse
import org.ntust.app.tigerduck.shared.Course

internal class ClassTableTermFetch(
    /**
     * One course as the school has it: QueryCourse's rows, or Moodle's
     * stand-in when it has none, or null for neither —
     * `CourseService.lookupOrFallback`. Tells `onFailed` of a query that
     * failed, which the stand-in otherwise hides.
     */
    private val lookUp: suspend (
        semester: String, courseNo: String, moodle: MoodleEnrolledCourse?, onFailed: () -> Unit,
    ) -> Course?,
) {
    /** What a term's fetch brought. */
    class Outcome(
        /** The term's list to show and save, or null when nothing came back and the term is left as it is. */
        val courses: List<Course>?,
        /** Whether every query went through; the warm of the other terms stops at one that did not. */
        val answered: Boolean,
    )

    /**
     * [rosterNos] looked up course by course, and with them the courses the
     * user added by hand, or that were merged from another device, that the
     * roster does not list (tigerduck-app-android#264):
     * [ClassTableCourseMerge.mergeFetched] keeps those as stored, so a room
     * or time the school changed since they were added never reached them.
     * One the query does not answer keeps its snapshot.
     *
     * [latest] is the term's list, read before the lookups for what to ask
     * about and again after them for what to fold the answers onto, so an
     * edit made while they ran is kept, and a course deleted then is not
     * brought back by its own answer. [deletedNos] is read after them too,
     * and applied whichever way the answers came.
     */
    suspend fun run(
        semester: String,
        rosterNos: List<String>,
        moodleByNo: Map<String, MoodleEnrolledCourse>,
        latest: suspend () -> List<Course>,
        names: () -> CustomNameMap,
        locale: String,
        deletedNos: suspend () -> Set<String>,
    ): Outcome = coroutineScope {
        val manualNos = ClassTableCourseMerge.manualNosToLookUp(latest(), rosterNos)
        if (rosterNos.isEmpty() && manualNos.isEmpty()) return@coroutineScope Outcome(null, answered = true)
        var failed = false
        val noteFailure = { failed = true }
        val roster = rosterNos.map { courseNo ->
            async { lookUp(semester, courseNo, moodleByNo[courseNo], noteFailure) }
        }
        // Without Moodle to fall back on: a hand-added course the query does
        // not answer keeps its snapshot, where a stub would take its times.
        val manual = manualNos.map { courseNo ->
            async { courseNo to lookUp(semester, courseNo, null, noteFailure) }
        }
        val fetched = roster.awaitAll().filterNotNull()
        val lookedUp = manual.awaitAll().mapNotNull { (courseNo, course) -> course?.let { courseNo to it } }.toMap()
        if (fetched.isEmpty() && lookedUp.isEmpty()) return@coroutineScope Outcome(null, answered = !failed)
        val deleted = deletedNos()
        val refreshed = ClassTableCourseMerge.withLookedUp(latest(), lookedUp)
        val courses = if (fetched.isNotEmpty()) {
            ClassTableCourseMerge.mergeFetched(fetched, refreshed, names(), locale, deleted)
        } else {
            // The roster brought nothing, so the term stays as it is, its
            // hand-added rows refreshed; a merge over nothing would drop the
            // rest.
            CourseNameOverrides.resolve(refreshed, names(), locale).filter { it.courseNo !in deleted }
        }
        Outcome(courses, answered = !failed)
    }
}
