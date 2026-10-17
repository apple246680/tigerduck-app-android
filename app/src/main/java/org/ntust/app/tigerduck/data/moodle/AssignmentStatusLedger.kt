package org.ntust.app.tigerduck.data.moodle

import org.ntust.app.tigerduck.data.model.Assignment
import java.util.Date

/** What an assignment's page found of the submission, with when its request went out. */
data class SubmissionStatusAnswer(
    val assignmentId: String,
    val submitted: Boolean,
    /** Epoch milliseconds; null for one not handed in. */
    val submittedAtMs: Long?,
    /** A monotonic clock's reading before the request went out. */
    val askedAtMs: Long,
)

/**
 * When Moodle was asked for the status each cached assignment last took, so an older answer
 * never overwrites a newer one: a round's answers against a page's, and one page's against
 * another's. Times come from a monotonic clock, as a wall clock set back would make a later
 * answer look older. Ported from iOS `AppServiceBridge.statusAskedAt`.
 *
 * A round's answers are written by the screen that ran it, some time after the round, so the
 * page's answers are kept here and laid over the round's list as it is written ([apply]) rather
 * than the page waiting the round out.
 */
class AssignmentStatusLedger {
    private val roundAskedAt = HashMap<String, Long>()
    private val pageAnswers = HashMap<String, SubmissionStatusAnswer>()

    /** Keeps, for each assignment a round answered for, when the round's own request went out. */
    @Synchronized
    fun recordRound(askedAtMs: Map<String, Long>) {
        for ((id, askedAt) in askedAtMs) {
            val known = roundAskedAt[id]
            if (known == null || known < askedAt) roundAskedAt[id] = askedAt
        }
    }

    /**
     * Keeps a page's [answer] for [apply], unless one asked for before the answer the assignment
     * holds, a round's or another page's; whether it was kept.
     */
    @Synchronized
    fun offer(answer: SubmissionStatusAnswer): Boolean {
        val id = answer.assignmentId
        val newest = maxOf(roundAskedAt[id] ?: Long.MIN_VALUE, pageAnswers[id]?.askedAtMs ?: Long.MIN_VALUE)
        if (newest >= answer.askedAtMs) return false
        pageAnswers[id] = answer
        return true
    }

    /** [assignments] with each page answer newer than the round's request for that assignment applied. */
    @Synchronized
    fun apply(assignments: List<Assignment>): List<Assignment> = assignments.map { assignment ->
        val page = pageAnswers[assignment.assignmentId] ?: return@map assignment
        val round = roundAskedAt[assignment.assignmentId]
        if (round != null && round >= page.askedAtMs) return@map assignment
        assignment.copy(isCompleted = page.submitted, submittedAt = page.submittedAtMs?.let(::Date))
    }

    /** Forgets everything, at a sign-out: the next account's answers are not ordered against this one's. */
    @Synchronized
    fun clear() {
        roundAskedAt.clear()
        pageAnswers.clear()
    }
}
