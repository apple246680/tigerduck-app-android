package org.ntust.app.tigerduck.ui.screen.score

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.ntust.app.tigerduck.data.model.CourseGrade
import org.ntust.app.tigerduck.mail.MainDispatcherRule
import org.ntust.app.tigerduck.shared.Course

/**
 * How a score row finds its class-table course, and how that course's schedule reads in the
 * course's detail (tigerduck-app-android#270). Mirrors iOS `ScoreCourseDetailSheet`.
 */
class ScoreCourseRosterTest {
    @get:Rule val main = MainDispatcherRule()

    private fun grade(code: String, term: String = "1141") =
        CourseGrade(term = term, code = code, name = "Course $code")

    private fun rosterCourse(courseNo: String) =
        Course.fromSchedule(courseNo = courseNo, courseName = "Roster $courseNo")

    @Test
    fun `a score row is the roster course with its course number`() {
        val calculus = rosterCourse("CS1001301")
        val roster = listOf(rosterCourse("CS1002302"), calculus)

        assertEquals(calculus, ScoreCourseRoster.match(roster, grade("CS1001301")))
    }

    @Test
    fun `a course number the roster lacks has no roster course`() {
        assertNull(ScoreCourseRoster.match(listOf(rosterCourse("CS1002302")), grade("CS1001301")))
        assertNull(ScoreCourseRoster.match(emptyList(), grade("CS1001301")))
    }

    /** A blank number would otherwise pair with a hand-added course that has none. */
    @Test
    fun `a score row without a course number matches nothing`() {
        val roster = listOf(rosterCourse(""), rosterCourse(" "))

        assertNull(ScoreCourseRoster.match(roster, grade("")))
        assertNull(ScoreCourseRoster.match(roster, grade("  ")))
    }

    @Test
    fun `surrounding whitespace does not keep a course from matching`() {
        val calculus = rosterCourse(" CS1001301 ")

        assertEquals(calculus, ScoreCourseRoster.match(listOf(calculus), grade("CS1001301 ")))
    }

    @Test
    fun `the schedule reads as the school's node codes, Monday first, periods in order`() {
        assertEquals(
            "M3, M4, T1",
            ScoreCourseRoster.scheduleCode(mapOf(2 to listOf("1"), 1 to listOf("4", "3"))),
        )
    }

    @Test
    fun `evening periods follow the tenth and every weekday has its letter`() {
        assertEquals(
            "M9, M10, MA, MB",
            ScoreCourseRoster.scheduleCode(mapOf(1 to listOf("A", "10", "B", "9"))),
        )
        assertEquals(
            "M1, T1, W1, R1, F1, S1, U1",
            ScoreCourseRoster.scheduleCode((1..7).associateWith { listOf("1") }),
        )
    }

    @Test
    fun `a weekday with no letter is left out and an unknown period goes last`() {
        assertEquals(
            "M2, MX",
            ScoreCourseRoster.scheduleCode(mapOf(1 to listOf("X", "2"), 8 to listOf("1"), 0 to listOf("1"))),
        )
    }

    @Test
    fun `a course with no periods has no schedule code`() {
        assertEquals("", ScoreCourseRoster.scheduleCode(emptyMap()))
        assertEquals("", ScoreCourseRoster.scheduleCode(mapOf(1 to emptyList())))
    }

    /** The header's "term · credits" line is the iOS template, whose first slot is `%1$@`. */
    @Test
    fun `the credits line fills both slots of the iOS template`() {
        assertEquals(
            "114 Fall · 3 credits",
            ScoreCourseRoster.creditsMeta("%1\$@ · %2\$s credits", "114 Fall", "3"),
        )
    }

    /** NTUST reissues course numbers across terms, so the roster asked for is the row's own term's. */
    @Test
    fun `the view model looks the course up in the roster of its own term`() = runTest {
        val termsAsked = mutableListOf<String>()
        val retake = rosterCourse("CS1001301")
        val vm = ScoreViewModel(
            authState = MutableStateFlow(true),
            storedStudentId = { "B11300001" },
            storedPassword = { "password" },
            cachedSnapshot = { null },
            fetchReport = { _, _, _ -> error("not fetched here") },
            isOnline = { false },
            isDemo = { false },
            strings = { "string $it" },
            rosterCourses = { term ->
                termsAsked += term
                if (term == "1132") listOf(retake) else emptyList()
            },
        )

        assertEquals(retake, vm.rosterCourse(grade("CS1001301", term = "1132")))
        assertNull(vm.rosterCourse(grade("CS1001301", term = "1141")))
        assertEquals(listOf("1132", "1141"), termsAsked)
    }
}
