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
}
