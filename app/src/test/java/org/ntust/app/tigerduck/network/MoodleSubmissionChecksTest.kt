package org.ntust.app.tigerduck.network

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.network.model.MoodleAssignmentNode
import org.ntust.app.tigerduck.network.model.MoodleSubmission
import org.ntust.app.tigerduck.network.model.MoodleSubmissionLastAttempt
import org.ntust.app.tigerduck.network.model.MoodleSubmissionStatusEnvelope
import java.util.Date

/**
 * Which assignments a round asks Moodle about. Every one not confirmed
 * submitted was asked, past its cutoff, without a due date or taking no
 * submissions included, every 15 minutes from the worker
 * (tigerduck-app-android#221).
 */
class MoodleSubmissionChecksTest {

    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L

    private fun node(id: Int, due: Long = now + hour, cutoff: Long? = null, noSubmissions: Boolean = false) =
        MoodleAssignmentNode(
            id = id,
            cmid = id,
            name = "A$id",
            duedate = due / 1000,
            cutoffdate = cutoff?.let { it / 1000 },
            nosubmissions = if (noSubmissions) 1 else 0,
        )

    private fun status(handedIn: Boolean, extensionUntil: Long? = null) = MoodleSubmissionStatusEnvelope(
        lastattempt = MoodleSubmissionLastAttempt(
            submission = MoodleSubmission(status = if (handedIn) "submitted" else "new", timemodified = 1),
            extensionduedate = extensionUntil?.let { it / 1000 },
        ),
    )

    @Test
    fun `no due date, no submissions and a confirmed submission are not asked about`() {
        val asked = MoodleService.assignmentsToAsk(
            listOf(node(1), node(2, due = 0), node(3, noSubmissions = true), node(4)),
            confirmed = mapOf(4 to Date(now)),
            closed = emptyMap(),
        )

        assertEquals(listOf(1), asked.map { it.id })
    }

    @Test
    fun `an assignment found unsubmitted after its cutoff is not asked about again`() {
        val cutoff = now - 2 * hour
        val asked = MoodleService.assignmentsToAsk(
            listOf(node(1, due = cutoff, cutoff = cutoff), node(2, due = cutoff, cutoff = cutoff)),
            confirmed = emptyMap(),
            // 1 was asked after its cutoff; 2 only before it, so a last-minute
            // submission may not have been seen yet.
            closed = mapOf(1 to now - hour, 2 to cutoff - hour),
        )

        assertEquals(listOf(2), asked.map { it.id })
    }

    @Test
    fun `a round closes what it found unsubmitted past the cutoff, and keeps what it did not ask`() {
        val cutoff = now - hour
        val assignments = listOf(
            node(1, due = cutoff, cutoff = cutoff),
            node(2, due = cutoff, cutoff = cutoff),
            node(3, due = now + hour, cutoff = now + 2 * hour),
            node(4, due = cutoff, cutoff = cutoff),
            node(5, due = cutoff, cutoff = cutoff),
            node(6, due = cutoff, cutoff = cutoff),
        )

        val closed = MoodleService.closedUnsubmitted(
            assignments,
            statuses = mapOf(
                1 to status(handedIn = false),
                2 to status(handedIn = true),
                3 to status(handedIn = false),
                4 to status(handedIn = false, extensionUntil = now + hour),
            ),
            askedAtMs = now,
            // 5 was closed by an earlier round and not asked this time; 6's
            // status call failed and nothing closed it before.
            kept = mapOf(5 to now - hour / 2, 9 to now - hour),
        )

        assertEquals(mapOf(1 to now, 5 to now - hour / 2), closed)
    }
}
