package org.ntust.app.tigerduck.liveactivity

import android.Manifest
import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.notification.ClassReminderAlarms
import org.ntust.app.tigerduck.notification.NotificationChannelRegistrar
import org.ntust.app.tigerduck.shared.Course
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What a refresh of the real manager arms for class reminders: their own
 * switch and lead time decide, never the Live Update's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LiveActivityManagerTest {

    /** Records what the manager asks of the class reminder alarms. */
    private class FakeClassReminderAlarms : ClassReminderAlarms {
        var armedLeadSec: Long? = null
        var armedCourses: List<Course> = emptyList()
        var cancels = 0

        override fun scheduleAll(
            courses: List<Course>,
            skippedDates: Map<String, List<String>>,
            leadTimeSec: Long,
            calendar: AcademicCalendar,
            optedInHolidayIds: Set<Int>,
        ) {
            armedLeadSec = leadTimeSec
            armedCourses = courses
        }

        override fun cancelAllTracked() {
            armedLeadSec = null
            cancels++
        }
    }

    private val context: Application = RuntimeEnvironment.getApplication()
    private val appPrefs = AppPreferences(context)
    private val liveUpdate = LiveActivityPreferences(context)
    private val calendar = AcademicCalendarStore(context, OkHttpClient(), appPrefs)
    private val dataCache = DataCache(context, calendar)
    private val alarms = FakeClassReminderAlarms()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
    private var signedIn = true

    private val course = Course(courseNo = "CS3005301", courseName = "Data Structures", scheduleJson = """{"1":["2","3"]}""")

    private val manager = LiveActivityManager(
        preferences = liveUpdate,
        notifier = LiveActivityNotifier(context, liveUpdate, appPrefs, NotificationChannelRegistrar(context, appPrefs)),
        dataCache = dataCache,
        isSignedIn = { signedIn },
        appPrefs = appPrefs,
        classPreparingScheduler = alarms,
        academicCalendar = calendar,
        boundaryScheduler = LiveActivityBoundaryScheduler(context),
        appScope = scope,
    )

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        runBlocking { dataCache.saveCourses(listOf(course)) }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun refresh() = runBlocking { manager.refreshAndWait() }

    @Test
    fun `with the Live Update off, class reminders are armed at their own lead time`() {
        liveUpdate.isEnabled = false
        liveUpdate.showClassPreparing = false
        appPrefs.classReminderEnabled = true
        appPrefs.classReminderLeadMinutes = 10

        refresh()

        assertEquals(10 * 60L, alarms.armedLeadSec)
        assertEquals(listOf("CS3005301"), alarms.armedCourses.map { it.courseNo })
    }

    @Test
    fun `with the Live Update on, its class-preparing lead time does not move the reminders`() {
        liveUpdate.isEnabled = true
        liveUpdate.showClassPreparing = true
        liveUpdate.classPreparingLeadTimeSec = 60 * 60
        appPrefs.classReminderEnabled = true
        appPrefs.classReminderLeadMinutes = 5

        refresh()

        assertEquals(5 * 60L, alarms.armedLeadSec)
    }

    @Test
    fun `with class reminders off, none are armed even with the Live Update on`() {
        liveUpdate.isEnabled = true
        liveUpdate.showClassPreparing = true
        appPrefs.classReminderEnabled = false

        refresh()

        assertNull(alarms.armedLeadSec)
        assertTrue(alarms.cancels > 0)
    }

    @Test
    fun `signed out, none are armed`() {
        signedIn = false
        appPrefs.classReminderEnabled = true

        refresh()

        assertNull(alarms.armedLeadSec)
        assertTrue(alarms.cancels > 0)
    }
}
