package org.ntust.app.tigerduck.notification

import android.app.AlarmManager
import android.app.Application
import java.util.Date
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Which reminders get armed, over what every caller hands the scheduler. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AssignmentNotificationSchedulerTest {

    private lateinit var context: Application
    private lateinit var cache: DataCache
    private lateinit var scheduler: AssignmentNotificationScheduler

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context)))
        scheduler = AssignmentNotificationScheduler(context)
    }

    private fun dueInDays(id: String, days: Long) = Assignment(
        assignmentId = id,
        courseNo = "CS1001301",
        courseName = "Course",
        title = "Task $id",
        dueDate = Date(AppClock.nowMillis() + days * 86_400_000),
    )

    /** The assignment id of every armed reminder, one per alarm. */
    // Robolectric 4.17 deprecates the operation field but has no getter for it.
    @Suppress("DEPRECATION")
    private fun armedIds(): List<String?> =
        shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.map {
            shadowOf(it.operation).savedIntent
                .getStringExtra(AssignmentNotificationReceiver.EXTRA_ASSIGNMENT_ID)
        }

    @Test
    fun `homework ignored or marked done gets no reminder, not even one`() = runTest {
        cache.saveAssignments(
            listOf(dueInDays("open", 3), dueInDays("ignored", 3), dueInDays("marked", 3))
        )
        cache.saveIgnoredAssignments(setOf("ignored"))
        cache.saveMarkedCompletedAssignments(setOf("marked"))

        scheduler.scheduleAll(
            cache.loadOutstandingAssignments(),
            setOf(AssignmentReminderOffset.HR24, AssignmentReminderOffset.HR2),
        )

        assertEquals(listOf("open", "open"), armedIds())
    }
}
