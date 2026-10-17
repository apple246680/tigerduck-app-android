// How a score row finds its class-table course, and how that course's
// schedule and the header's term line read in the course's detail. Mirrors
// iOS ScoreCourseDetailSheet.resolveRosterCourse / formatScheduleCode.

package org.ntust.app.tigerduck.ui.screen.score

import org.ntust.app.tigerduck.data.model.CourseGrade
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.periodOrder
import org.ntust.app.tigerduck.util.replaceIosArg

internal object ScoreCourseRoster {

    /**
     * The course of [roster] that [course] is, by course number, or null.
     * [roster] is the cached class table of the course's own term, portal
     * rows and hand-added ones together as `DataCache.loadCourses` returns
     * them — iOS searches its user-added list separately only because it
     * keeps that list outside the term's cache. A blank number matches
     * nothing: a hand-added course may carry none, and it is not this one.
     */
    fun match(roster: List<Course>, course: CourseGrade): Course? {
        val code = course.code.trim()
        if (code.isEmpty()) return null
        return roster.firstOrNull { it.courseNo.trim() == code }
    }

    /**
     * Weekday number to the letter the school's course-selection pages use,
     * the inverse of what CourseLookupService reads: M T W R F S U.
     */
    private val dayCodes = mapOf(1 to "M", 2 to "T", 3 to "W", 4 to "R", 5 to "F", 6 to "S", 7 to "U")

    /**
     * [schedule] as the node string the school prints, e.g. "M3, M4, T1":
     * weekdays Monday to Sunday, and within a day the periods as taught, so
     * the evening slots A–D follow 10 instead of sorting before 1. A period
     * the app does not know goes last, as on iOS, and a weekday without a
     * letter is dropped rather than written as a bare number.
     */
    fun scheduleCode(schedule: Map<Int, List<String>>): String =
        schedule.entries
            .sortedBy { it.key }
            .flatMap { (weekday, periods) ->
                val day = dayCodes[weekday] ?: return@flatMap emptyList<String>()
                periods
                    .sortedBy { periodOrder(it).takeIf { order -> order >= 0 } ?: Int.MAX_VALUE }
                    .map { day + it }
            }
            .joinToString(", ")

    /**
     * The header's "term · credits" line from `score_course_credits_meta`.
     * The submodule writes that template for iOS, with `%1$@` in the first
     * slot and `%2$s` in the second; Java's formatter rejects the `@`, so
     * both slots are filled by replacement rather than by `String.format`.
     */
    fun creditsMeta(template: String, term: String, credits: String): String =
        template.replaceIosArg(1, term).replace("%2\$s", credits)
}
