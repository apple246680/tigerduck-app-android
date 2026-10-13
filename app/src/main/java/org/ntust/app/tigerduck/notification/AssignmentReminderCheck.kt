package org.ntust.app.tigerduck.notification

import android.util.Log
import kotlinx.coroutines.CancellationException
import org.ntust.app.tigerduck.data.CourseIgnores
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.network.MoodleService
import org.ntust.app.tigerduck.network.MoodleWebserviceError
import org.ntust.app.tigerduck.network.SubmissionStatusProbe
import org.ntust.app.tigerduck.util.logSummary
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether a reminder that just fired is still due, by
 * [AssignmentReminderPolicy]: the cache first, then one status call to Moodle.
 * A submission Moodle reports goes into the cache and cancels the assignment's
 * other reminders, so they need no call of their own.
 */
@Singleton
class AssignmentReminderCheck @Inject constructor(
    private val dataCache: DataCache,
    private val scheduler: AssignmentNotificationScheduler,
    private val statusProbe: SubmissionStatusProbe,
) {

    suspend fun verdict(assignmentId: String, armedDueAtMs: Long?): ReminderVerdict {
        // A sign-out while this runs wipes the data; nothing is written back after it.
        val generation = dataCache.userDataGeneration
        AssignmentReminderPolicy.cacheDrop(cached(assignmentId), armedDueAtMs)
            ?.let { return ReminderVerdict.Drop(it) }

        val moodleId = assignmentId.toIntOrNull()
        val answer = moodleId?.let { askMoodle(it) } ?: ReminderStatusAnswer.NoAnswer
        // Read again: a mark, an ignore or a new due date can land while Moodle answers.
        val cached = cached(assignmentId)
        if (moodleId != null && answer is ReminderStatusAnswer.Answered) {
            val (submitted, submittedAt) =
                MoodleService.submissionState(moodleId, emptyMap(), answer.lastAttempt)
            if (submitted) recordSubmission(assignmentId, submittedAt, generation)
        }
        return AssignmentReminderPolicy.decide(cached, armedDueAtMs, answer)
    }

    private suspend fun cached(assignmentId: String): CachedReminderAssignment? {
        val assignment = dataCache.loadAssignments().firstOrNull { it.assignmentId == assignmentId }
            ?: return null
        val ofIgnoredCourse = CourseIgnores.assignmentIds(
            listOf(assignment), dataCache.loadIgnoredCourses(), dataCache.currentSemesterCode(),
        )
        return CachedReminderAssignment(
            dueAtMs = assignment.dueDate.time,
            submitted = assignment.isCompleted,
            markedDone = assignmentId in dataCache.loadMarkedCompletedAssignments(),
            ignored = assignmentId in dataCache.loadIgnoredAssignments(),
            courseIgnored = assignmentId in ofIgnoredCourse,
        )
    }

    /** What a sync that asked Moodle would store, and no more reminders for it. */
    private suspend fun recordSubmission(assignmentId: String, submittedAt: Date?, generation: Int) {
        dataCache.updateAssignments(generation) { assignments ->
            assignments.map {
                if (it.assignmentId != assignmentId) it
                else it.copy(isCompleted = true, submittedAt = submittedAt ?: it.submittedAt)
            }
        }
        scheduler.cancel(assignmentId)
    }

    private suspend fun askMoodle(moodleId: Int): ReminderStatusAnswer = try {
        statusProbe.status(moodleId)
            ?.let { ReminderStatusAnswer.Answered(it.lastattempt) }
            ?: ReminderStatusAnswer.NoToken
    } catch (e: CancellationException) {
        throw e
    } catch (_: MoodleWebserviceError.InvalidToken) {
        ReminderStatusAnswer.TokenRejected
    } catch (e: Exception) {
        Log.w(TAG, "status call failed: ${e.logSummary()}")
        ReminderStatusAnswer.NoAnswer
    }

    private companion object {
        const val TAG = "AssignmentReminderCheck"
    }
}
