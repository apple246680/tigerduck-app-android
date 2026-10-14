package org.ntust.app.tigerduck.liveactivity

import android.app.Application
import java.time.ZonedDateTime
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.AppLink
import org.ntust.app.tigerduck.data.OutstandingAssignments
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.shared.Course
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The "assignment due soon" Live Update over what LiveActivityManager hands
 * the resolver: the outstanding assignments, not every cached one; and what
 * a tap on each opens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LiveActivityResolverTest {

    private val now = Date(1_700_000_000_000)
    private lateinit var preferences: LiveActivityPreferences

    @Before
    fun setUp() {
        preferences = LiveActivityPreferences(RuntimeEnvironment.getApplication())
    }

    private fun dueInHours(id: String, hours: Long) = Assignment(
        assignmentId = id,
        courseNo = "CS1001301",
        courseName = "Course",
        title = "Task $id",
        dueDate = Date(now.time + hours * 3_600_000),
    )

    private fun resolve(assignments: List<Assignment>) = LiveActivityResolver().resolve(
        courses = emptyList(),
        assignments = assignments,
        skippedDates = emptyMap(),
        preferences = preferences,
        accentHex = 0,
        now = now,
    )

    @Test
    fun `an ignored assignment due soon starts nothing`() {
        val all = listOf(dueInHours("ignored", 1))

        val snapshot = resolve(OutstandingAssignments.of(all, setOf("ignored"), emptySet()))

        assertNull(snapshot)
    }

    @Test
    fun `the next outstanding assignment is shown, not an earlier one marked done`() {
        val all = listOf(dueInHours("marked", 1), dueInHours("open", 2))

        val snapshot = resolve(OutstandingAssignments.of(all, emptySet(), setOf("marked")))

        assertEquals("assignment-open", snapshot?.sourceId)
    }

    // Wednesday 16 September 2026 in Taipei; the course meets periods 3–4,
    // 10:20–12:10.
    private fun taipei(hour: Int, minute: Int): Date = Date.from(
        ZonedDateTime.of(2026, 9, 16, hour, minute, 0, 0, AppConstants.TAIPEI_ZONE).toInstant()
    )

    private val wednesdayCourse = Course(
        courseNo = "CS3005301",
        courseName = "Data Structures",
        scheduleJson = """{"3":["3","4"]}""",
    )

    private fun dueAt(id: String, due: Date) = Assignment(
        assignmentId = id,
        courseNo = "CS3204301",
        courseName = "Operating Systems",
        title = "Lab $id",
        dueDate = due,
    )

    private fun resolveAt(at: Date, assignments: List<Assignment>) = LiveActivityResolver().resolve(
        courses = listOf(wednesdayCourse),
        assignments = assignments,
        skippedDates = emptyMap(),
        preferences = preferences,
        accentHex = 0,
        now = at,
    )

    @Test
    fun `the due-soon Live Update opens its assignment, and a class the class table`() {
        val assignment = resolveAt(taipei(8, 0), listOf(dueAt("900002", taipei(14, 0))))
        preferences.showAssignment = false
        val inClass = resolveAt(taipei(10, 30), emptyList())
        val preparing = resolveAt(taipei(10, 10), emptyList())

        assertEquals(AppLink.Assignment("900002"), assignment?.link)
        assertEquals(AppLink.ClassTable, inClass?.link)
        assertEquals(AppLink.ClassTable, preparing?.link)
    }
}
