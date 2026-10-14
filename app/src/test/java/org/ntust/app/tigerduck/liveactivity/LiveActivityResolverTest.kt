package org.ntust.app.tigerduck.liveactivity

import android.app.Application
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.OutstandingAssignments
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.shared.Course
import java.time.ZonedDateTime
import org.ntust.app.tigerduck.AppConstants
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The "assignment due soon" Live Update over what LiveActivityManager hands
 * the resolver: the outstanding assignments, not every cached one.
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

    // A course meeting twice on Mondays, in a different room each time.
    private val twice = Course.fromSchedule(
        courseNo = "CS2001301",
        courseName = "Data Structures",
        classroom = "TR-313, TR-412",
        schedule = mapOf(1 to listOf("3", "4", "7", "8")),
        classroomMap = mapOf(
            "1-3" to "TR-313", "1-4" to "TR-313",
            "1-7" to "TR-412", "1-8" to "TR-412",
        ),
    )

    // 2026-10-12 is a Monday.
    private fun mondayAt(hour: Int, minute: Int) = Date.from(
        ZonedDateTime.of(2026, 10, 12, hour, minute, 0, 0, AppConstants.TAIPEI_ZONE).toInstant()
    )

    private fun resolveClass(at: Date) = LiveActivityResolver().resolve(
        courses = listOf(twice),
        assignments = emptyList(),
        skippedDates = emptyMap(),
        preferences = preferences,
        accentHex = 0,
        now = at,
    )

    @Test
    fun `the morning block of a course meeting twice shows its own room`() {
        val snapshot = resolveClass(mondayAt(10, 30))!!

        assertEquals("10:20–12:10", snapshot.subtitle)
        assertEquals("TR-313", snapshot.locationText)
        assertEquals("class-CS2001301-3", snapshot.sourceId)
    }

    @Test
    fun `the afternoon block of a course meeting twice is a block of its own`() {
        val snapshot = resolveClass(mondayAt(15, 0))!!

        assertEquals("14:20–16:20", snapshot.subtitle)
        assertEquals("TR-412", snapshot.locationText)
        assertEquals("class-CS2001301-7", snapshot.sourceId)
    }
}
