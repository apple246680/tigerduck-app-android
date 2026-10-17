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
 * The newest answer accepted for each cached assignment's status, with when Moodle was asked for
 * it, so an older answer never overwrites a newer one: a round's answers against a page's, and
 * one page's against another's. Times come from a monotonic clock, as a wall clock set back would
 * make a later answer look older. Ported from iOS `AppServiceBridge.statusAskedAt`.
 *
 * A round's answers are written by the screen that ran it, some time after the round, so each
 * round's list is reconciled against the accepted answers as it is written ([reconcile]), by the
 * round's own request times, rather than the page waiting the round out. Both the round's write
 * and a page's run under the cache's lock, so the two cannot pass each other.
 */
class AssignmentStatusLedger {
    private data class Accepted(val askedAtMs: Long, val submitted: Boolean, val submittedAtMs: Long?)

    private val accepted = HashMap<String, Accepted>()

    /**
     * Keeps a page's [answer] as the assignment's accepted one, unless one asked for as late or
     * later is accepted, a round's or another page's; whether it was kept.
     */
    @Synchronized
    fun offer(answer: SubmissionStatusAnswer): Boolean {
        val current = accepted[answer.assignmentId]
        if (current != null && current.askedAtMs >= answer.askedAtMs) return false
        accepted[answer.assignmentId] = Accepted(answer.askedAtMs, answer.submitted, answer.submittedAtMs)
        return true
    }

    /**
     * [assignments] as a round writes them, [askedAtMs] being when its own request for each went
     * out, by id: an assignment whose accepted answer is newer than that request takes the
     * accepted answer, and the rest take the round's, which is accepted from here on. One the
     * round did not ask about, kept from the cache, takes any accepted answer and records none.
     */
    @Synchronized
    fun reconcile(assignments: List<Assignment>, askedAtMs: Map<String, Long>): List<Assignment> =
        assignments.map { assignment ->
            val id = assignment.assignmentId
            val asked = askedAtMs[id]
            val current = accepted[id]
            when {
                current != null && (asked == null || current.askedAtMs > asked) ->
                    assignment.copy(isCompleted = current.submitted, submittedAt = current.submittedAtMs?.let(::Date))
                asked != null -> {
                    accepted[id] = Accepted(asked, assignment.isCompleted, assignment.submittedAt?.time)
                    assignment
                }
                else -> assignment
            }
        }

    /** Forgets everything, at a sign-out: the next account's answers are not ordered against this one's. */
    @Synchronized
    fun clear() {
        accepted.clear()
    }
}
