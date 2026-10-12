package org.ntust.app.tigerduck.notification

import android.app.AlarmManager
import android.app.Application
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
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
import java.util.Date

/**
 * The reminders the class table and the calendar rebuild after they write
 * the assignment cache, an empty write included.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class AssignmentRescheduleFromCacheTest {

    private lateinit var context: Application
    private lateinit var prefs: AppPreferences
    private lateinit var dataCache: DataCache
    private lateinit var scheduler: AssignmentNotificationScheduler

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs = AppPreferences(context)
        dataCache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), prefs))
        scheduler = AssignmentNotificationScheduler(context)
    }

    private fun reminders() =
        shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms

    private fun dueInThreeDays(id: String) = Assignment(
        assignmentId = id,
        courseNo = "CS101",
        courseName = "CS",
        title = "HW $id",
        dueDate = Date(AppClock.nowMillis() + 3 * DAY_MS),
    )

    @Test
    fun `the cached assignments get their reminders`() = runBlocking {
        dataCache.saveAssignments(listOf(dueInThreeDays("1")))

        scheduler.rescheduleFromCache(dataCache, prefs)

        assertTrue(reminders().isNotEmpty())
    }

    @Test
    fun `an emptied cache takes every reminder away`() = runBlocking {
        dataCache.saveAssignments(listOf(dueInThreeDays("1")))
        scheduler.rescheduleFromCache(dataCache, prefs)

        // What a TA account's cache holds once Moodle leaves out the courses it TAs.
        dataCache.saveAssignments(emptyList())
        scheduler.rescheduleFromCache(dataCache, prefs)

        assertTrue(reminders().isEmpty())
    }

    @Test
    fun `nothing is armed while reminders are off`() = runBlocking {
        prefs.notifyAssignments = false
        dataCache.saveAssignments(listOf(dueInThreeDays("1")))

        scheduler.rescheduleFromCache(dataCache, prefs)

        assertTrue(reminders().isEmpty())
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
