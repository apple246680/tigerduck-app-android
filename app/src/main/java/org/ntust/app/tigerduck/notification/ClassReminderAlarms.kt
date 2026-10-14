package org.ntust.app.tigerduck.notification

import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.shared.Course

/**
 * The class reminder alarms, as LiveActivityManager arms and cancels them;
 * [ClassPreparingNotificationScheduler] is the real one. An interface so what
 * the manager decides can be checked against a fake.
 */
interface ClassReminderAlarms {
    /**
     * Arms a reminder [leadTimeSec] ahead of each class of [courses] in the
     * days ahead, replacing every one armed before.
     */
    fun scheduleAll(
        courses: List<Course>,
        skippedDates: Map<String, List<String>>,
        leadTimeSec: Long,
        calendar: AcademicCalendar,
        optedInHolidayIds: Set<Int>,
    )

    /** Cancels every reminder armed. */
    fun cancelAllTracked()
}
