package org.ntust.app.tigerduck.notification

import android.app.AlarmManager
import android.app.Application
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.CourseIgnores
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.model.MoodleSubmission
import org.ntust.app.tigerduck.network.model.MoodleSubmissionLastAttempt
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Date

/** What the check does with the cache and the armed reminders around Moodle's answer. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class AssignmentReminderCheckTest {

    private lateinit var context: Application
    private lateinit var dataCache: DataCache
    private lateinit var scheduler: AssignmentNotificationScheduler
    private lateinit var check: AssignmentReminderCheck

    private val asked = mutableListOf<Int>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        dataCache =
            DataCache(context, AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context)))
        scheduler = AssignmentNotificationScheduler(context)
        check = AssignmentReminderCheck(dataCache, scheduler) { error("the tests answer for Moodle") }
    }

    private fun dueInThreeDays(id: String) = Assignment(
        assignmentId = id,
        courseNo = "CS1001301",
        courseName = "Course",
        title = "Task $id",
        dueDate = Date(AppClock.nowMillis() + 3 * DAY_MS),
    )

    private fun answering(own: String?, team: String? = null): suspend (Int) -> ReminderStatusAnswer = { id ->
        asked += id
        ReminderStatusAnswer.Answered(
            MoodleSubmissionLastAttempt(
                submission = own?.let { MoodleSubmission(it, SUBMITTED_AT_SECONDS) },
                teamsubmission = team?.let { MoodleSubmission(it, SUBMITTED_AT_SECONDS) },
                submissionsenabled = true,
                locked = false,
            )
        )
    }

    /** The assignment id of every armed reminder, one per alarm. */
    // Robolectric 4.17 deprecates the operation field but has no getter for it.
    @Suppress("DEPRECATION")
    private fun armedIds(): List<String?> =
        shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.map {
            shadowOf(it.operation).savedIntent
                .getStringExtra(AssignmentNotificationReceiver.EXTRA_ASSIGNMENT_ID)
        }

    private suspend fun armAll(vararg assignments: Assignment) {
        dataCache.saveAssignments(assignments.toList())
        scheduler.scheduleAll(
            dataCache.loadOutstandingAssignments(),
            setOf(AssignmentReminderOffset.HR24, AssignmentReminderOffset.HR2),
        )
    }

    @Test
    fun `a reminder still due shows and leaves the cache and the other reminders alone`() = runBlocking {
        val assignment = dueInThreeDays("20977")
        armAll(assignment)

        val verdict = check.verdict("20977", assignment.dueDate.time, answering(own = "new"))

        assertEquals(ReminderVerdict.Show, verdict)
        assertEquals(listOf(20977), asked)
        assertEquals(listOf(assignment), dataCache.loadAssignments())
        assertEquals(listOf("20977", "20977"), armedIds())
    }

    @Test
    fun `a reminder for an assignment never started shows and leaves the cache alone`() = runBlocking {
        val assignment = dueInThreeDays("20977")
        armAll(assignment)

        val verdict = check.verdict("20977", assignment.dueDate.time, answering(own = null))

        assertEquals(ReminderVerdict.Show, verdict)
        assertEquals(listOf(assignment), dataCache.loadAssignments())
        assertEquals(listOf("20977", "20977"), armedIds())
    }

    @Test
    fun `a teammate's submission is recorded and the assignment's other reminders cancelled`() =
        runBlocking {
            val assignment = dueInThreeDays("20977")
            val other = dueInThreeDays("20978")
            armAll(assignment, other)

            val verdict = check.verdict(
                "20977", assignment.dueDate.time, answering(own = null, team = "submitted"),
            )

            assertEquals(ReminderVerdict.Drop(ReminderDropReason.SUBMITTED_ON_MOODLE), verdict)
            assertEquals(
                listOf(
                    assignment.copy(isCompleted = true, submittedAt = Date(SUBMITTED_AT_SECONDS * 1000)),
                    other,
                ),
                dataCache.loadAssignments(),
            )
            assertEquals(listOf("20978", "20978"), armedIds())
        }

    @Test
    fun `what the cache rules out asks Moodle nothing`() = runBlocking {
        val marked = dueInThreeDays("20977")
        val ofIgnoredCourse = dueInThreeDays("20978").copy(courseNo = "IGNORED")
        val moved = dueInThreeDays("20979")
        dataCache.saveAssignments(listOf(marked, ofIgnoredCourse, moved))
        dataCache.saveMarkedCompletedAssignments(setOf("20977"))
        dataCache.saveIgnoredCourses(setOf(CourseIgnores.key(dataCache.currentSemesterCode(), "IGNORED")))
        val ask = answering(own = "new")

        assertEquals(
            listOf(
                ReminderVerdict.Drop(ReminderDropReason.MARKED_DONE),
                ReminderVerdict.Drop(ReminderDropReason.COURSE_IGNORED),
                ReminderVerdict.Drop(ReminderDropReason.DUE_DATE_CHANGED),
                ReminderVerdict.Drop(ReminderDropReason.NOT_IN_CACHE),
            ),
            listOf(
                check.verdict("20977", marked.dueDate.time, ask),
                check.verdict("20978", ofIgnoredCourse.dueDate.time, ask),
                check.verdict("20979", moved.dueDate.time - DAY_MS, ask),
                check.verdict("20980", null, ask),
            ),
        )
        assertEquals(emptyList<Int>(), asked)
    }

    @Test
    fun `an id Moodle does not number is not asked about`() = runBlocking {
        val manual = dueInThreeDays("manual-1")
        dataCache.saveAssignments(listOf(manual))

        val verdict = check.verdict("manual-1", manual.dueDate.time, answering(own = "new"))

        assertEquals(ReminderVerdict.Drop(ReminderDropReason.NO_ANSWER), verdict)
        assertEquals(emptyList<Int>(), asked)
    }

    @Test
    fun `an answer that outlived a sign-out is not written into the next account's cache`() =
        runBlocking {
            val assignment = dueInThreeDays("20977")
            dataCache.saveAssignments(listOf(assignment))
            val submitted = answering(own = "submitted")

            check.verdict("20977", assignment.dueDate.time) { id ->
                dataCache.clearAllUserData()
                // The next account, with the same assignment still to do.
                dataCache.saveAssignments(listOf(assignment))
                submitted(id)
            }

            assertEquals(listOf(assignment), dataCache.loadAssignments())
        }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val SUBMITTED_AT_SECONDS = 1_760_400_000L
    }
}
