package org.ntust.app.tigerduck.notification

import android.app.AlarmManager
import android.app.Application
import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.shared.clock.ClockOverride
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZonedDateTime

/** What each "class starting soon" reminder is armed to say. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ClassPreparingNotificationSchedulerTest {

    private lateinit var context: Application
    private lateinit var scheduler: ClassPreparingNotificationScheduler

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

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        scheduler = ClassPreparingNotificationScheduler(context)
        // 2026-10-12 is a Monday.
        val mondayMorning = ZonedDateTime.of(2026, 10, 12, 7, 0, 0, 0, AppConstants.TAIPEI_ZONE)
        AppClock.setOverride(
            ClockOverride(mondayMorning.toInstant().toEpochMilli(), frozen = true, savedAtRealMillis = 0L)
        )
    }

    @After
    fun releaseClock() {
        AppClock.setOverride(null)
    }

    /** The intent of every armed reminder, in the order the classes start. */
    // Robolectric 4.17 deprecates the operation field but has no getter for it.
    @Suppress("DEPRECATION")
    private fun armed(): List<Intent> =
        shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
            .map { shadowOf(it.operation).savedIntent }
            .sortedBy { it.getLongExtra(ClassPreparingNotificationReceiver.EXTRA_START_MS, 0L) }

    @Test
    fun `each block of a course meeting twice is announced with its own room`() {
        scheduler.scheduleAll(listOf(twice), emptyMap(), leadTimeSec = 600)

        assertEquals(
            listOf("TR-313", "TR-412"),
            armed().take(2).map { it.getStringExtra(ClassPreparingNotificationReceiver.EXTRA_CLASSROOM) },
        )
    }
}
