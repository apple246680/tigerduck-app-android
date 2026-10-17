package org.ntust.app.tigerduck.data.model

import org.ntust.app.tigerduck.shared.clock.AppClock
import java.util.Date

data class Assignment(
    val assignmentId: String,
    val courseNo: String,
    val courseName: String,
    val title: String,
    val dueDate: Date,
    val isCompleted: Boolean = false,
    val moodleUrl: String? = null,
    /** Final cutoff after which Moodle rejects further submissions. Null when
     *  Moodle reports cutoffdate=0 (late submissions accepted indefinitely). */
    val cutoffDate: Date? = null,
    /** Moodle `submission.timemodified` — used to distinguish on-time vs late
     *  submissions once [isCompleted] is true. */
    val submittedAt: Date? = null,
    /**
     * The term the assignment was fetched for, which decides the course
     * ignore it falls under (see [org.ntust.app.tigerduck.data.CourseIgnores]).
     * Null in a cache written before it existed: the term in session then.
     */
    val semester: String? = null,
) {
    val isOverdue: Boolean
        get() = !isCompleted && dueDate.before(Date(AppClock.nowMillis()))

    /**
     * The Moodle app's link to the assignment's page, or null when Moodle gave
     * no address ([moodleUrl]): the demo account's homework, and a cache from
     * before v2.0 until its next refresh. Nothing is guessed from
     * [assignmentId] then, as nothing is on iOS: it is the `assign` instance
     * id, not the course-module id `mod/assign/view.php` takes, so a page
     * built from it was someone else's (tigerduck-app-android#313). An
     * assignment without a link opens nothing.
     */
    val moodleDeepLink: String?
        get() = moodleUrl?.takeIf { it.isNotBlank() }?.let {
            val path = it.substringAfter("moodle2.ntust.edu.tw")
            "moodlemobile://https://moodle2.ntust.edu.tw?redirect=$path"
        }
}
