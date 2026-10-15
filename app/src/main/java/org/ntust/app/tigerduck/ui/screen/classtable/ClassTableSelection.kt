// What the class table can say about a course once you know which cell the
// user tapped, and what "now" is.
//
// All of it derives from a course plus a weekday, a period, or a wall-clock
// minute — no state, no I/O — so it is here rather than on the ViewModel,
// where reaching any of it meant standing up Hilt.

package org.ntust.app.tigerduck.ui.screen.classtable

import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.academic.QuietDays
import org.ntust.app.tigerduck.shared.Course
import java.time.LocalDate
import java.util.Calendar

object ClassTableSelection {

    /**
     * Whether a course picked now is someone else's, which its detail shows read-only: one picked
     * from the class table while it shows someone else's. One picked from the live term, as Home
     * and the today strip pick it, is the user's own whatever the class table shows, as iOS opens
     * it from Home.
     */
    fun isSomeoneElses(showingSomeoneElse: Boolean, fromLiveTerm: Boolean): Boolean =
        showingSomeoneElse && !fromLiveTerm

    /**
     * The course the detail shows for [selected], as it is now, so a rename made from the detail
     * shows at once: the copy in the term it was picked from. That is [courses], the grid's term,
     * unless it was picked [fromAnotherTerm], the live term the today strip shows while the grid
     * is on another, whose copy is in [liveCourses]; one course number can be in both. A course
     * no longer listed keeps the copy that was picked.
     */
    fun detailCourse(
        selected: Course?,
        courses: List<Course>,
        liveCourses: List<Course>,
        fromAnotherTerm: Boolean,
    ): Course? {
        selected ?: return null
        val term = if (fromAnotherTerm) liveCourses else courses
        return term.firstOrNull { it.courseNo == selected.courseNo } ?: selected
    }

    /**
     * The course a rename of [courseNo] is measured against: the grid term's copy, else the live
     * term's, for a course picked from the today strip that the grid's term does not list.
     */
    fun renameTarget(courseNo: String, courses: List<Course>, liveCourses: List<Course>): Course? =
        courses.firstOrNull { it.courseNo == courseNo } ?: liveCourses.firstOrNull { it.courseNo == courseNo }

    /** [courses] with [courseNo]'s own name set to [name], or cleared with null. */
    fun renamed(courses: List<Course>, courseNo: String, name: String?): List<Course> =
        courses.map { if (it.courseNo == courseNo) it.copy(customCourseName = name) else it }

    /** The detail's title: the user's own name for the course, else its [fullName], else its name. */
    fun detailTitle(course: Course, fullName: String?): String =
        course.customCourseName ?: fullName ?: course.displayName

    /** Weekday (Mon=1 … Sun=7) paired with minutes since midnight. */
    data class DayTime(val weekday: Int, val minuteOfDay: Int)

    /**
     * Read a [DayTime] off a calendar. Monday-first, because that is how
     * course schedules are keyed; `Calendar` is Sunday-first.
     */
    fun dayTimeFrom(calendar: Calendar): DayTime {
        return DayTime(
            weekday = AppConstants.weekdayIndex(calendar.get(Calendar.DAY_OF_WEEK)),
            minuteOfDay = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE),
        )
    }

    /**
     * Today's courses in the order they are taught.
     *
     * Sorted by the chronological index of each course's *first* period today
     * rather than by period id, because period ids are not lexicographically
     * ordered — "10" sorts before "2", and the lunch and evening periods are
     * lettered. A course with no resolvable period sinks to the end instead
     * of throwing off the whole ordering.
     */
    fun coursesOn(courses: List<Course>, weekday: Int): List<Course> = courses
        .filter { it.schedule.containsKey(weekday) }
        .sortedBy { course ->
            course.schedule[weekday]
                ?.minByOrNull { AppConstants.Periods.chronologicalOrder.indexOf(it) }
                ?.let { AppConstants.Periods.chronologicalOrder.indexOf(it) }
                ?: Int.MAX_VALUE
        }

    /**
     * The today carousel's courses: [coursesOn] for [today], or none on a
     * quiet day, which hides the carousel as a day without classes does. The
     * timetable is filled weeks before 開學, and no class meets on a holiday
     * the user has not opted back into.
     */
    fun todayCourses(courses: List<Course>, today: LocalDate, quietDays: QuietDays): List<Course> =
        if (today in quietDays) emptyList() else coursesOn(courses, today.dayOfWeek.value)

    /**
     * "09:10 - 12:00" for the tapped day, spanning the course's first to last
     * period on it. Null when the course does not meet that day, or when a
     * period has no time mapping — better a missing line than a wrong one.
     */
    fun timeRange(course: Course, weekday: Int): String? {
        val periods = course.schedule[weekday]?.sortedBy {
            AppConstants.Periods.chronologicalOrder.indexOf(it)
        } ?: return null
        if (periods.isEmpty()) return null
        val first = AppConstants.PeriodTimes.mapping[periods.first()] ?: return null
        val last = AppConstants.PeriodTimes.mapping[periods.last()] ?: return null
        return "${first.first} - ${last.second}"
    }

    /**
     * The room for the tapped slot, narrowing as far as the caller can say.
     *
     * A course legitimately meets in different rooms on different days, so
     * tapping a Wednesday cell must not show Monday's room. With no weekday
     * the best available answer is the de-duplicated union across the week;
     * with a weekday but no period, the day's room.
     */
    fun classroom(course: Course, weekday: Int?, periodId: String?): String = when {
        weekday == null -> Course.dedupRooms(course.classroom)
        periodId == null -> course.classroom(weekday)
        else -> course.classroom(weekday, periodId)
    }

    /**
     * True once the course's last period today has ended.
     *
     * False whenever that cannot be established — the course does not meet
     * today, or a period is missing a time mapping. Used to grey out finished
     * classes, so guessing "finished" would hide a class that is still on.
     */
    fun isFinishedAt(course: Course, dayTime: DayTime): Boolean {
        val lastPeriodId = course.schedule[dayTime.weekday]
            ?.sortedBy { AppConstants.Periods.chronologicalOrder.indexOf(it) }
            ?.lastOrNull() ?: return false
        val endMinutes = minuteOfDay(
            AppConstants.PeriodTimes.mapping[lastPeriodId]?.second ?: return false
        ) ?: return false
        return dayTime.minuteOfDay > endMinutes
    }

    /** "14:20" -> 860. Null on anything that is not HH:MM. */
    private fun minuteOfDay(clockTime: String): Int? {
        val parts = clockTime.split(":")
        val hours = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minutes = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return hours * 60 + minutes
    }
}
