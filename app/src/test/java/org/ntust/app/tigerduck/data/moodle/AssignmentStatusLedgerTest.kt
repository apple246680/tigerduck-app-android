package org.ntust.app.tigerduck.data.moodle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.model.Assignment
import java.util.Date

/**
 * An answer asked for before the one the assignment holds, a round's or a page's, is dropped, so
 * a round that asked before a page and answers after it cannot overwrite the page's status.
 */
class AssignmentStatusLedgerTest {

    private fun row(id: String, completed: Boolean = false, submittedAtMs: Long? = null) = Assignment(
        assignmentId = id, courseNo = "CS3005301", courseName = "DS", title = "A$id", dueDate = Date(1_790_600_000_000L),
        isCompleted = completed, submittedAt = submittedAtMs?.let(::Date),
    )

    private fun answer(id: String, askedAtMs: Long, submitted: Boolean = true, submittedAtMs: Long? = 1_790_500_000_000L) =
        SubmissionStatusAnswer(id, submitted, submittedAtMs, askedAtMs)

    @Test
    fun `a page answer newer than the round's request replaces the round's status at the write`() {
        val ledger = AssignmentStatusLedger()
        ledger.recordRound(mapOf("701" to 100L, "702" to 100L))
        assertTrue(ledger.offer(answer("701", askedAtMs = 150L)))

        val written = ledger.apply(listOf(row("701"), row("702")))

        assertTrue(written[0].isCompleted)
        assertEquals(Date(1_790_500_000_000L), written[0].submittedAt)
        assertFalse("an assignment with no page answer keeps the round's status", written[1].isCompleted)
    }

    @Test
    fun `an answer asked for before the one the assignment has is dropped`() {
        val ledger = AssignmentStatusLedger()
        ledger.recordRound(mapOf("701" to 200L))

        assertFalse("older than the round's", ledger.offer(answer("701", askedAtMs = 150L)))
        assertTrue(ledger.offer(answer("701", askedAtMs = 250L, submitted = false, submittedAtMs = null)))
        assertFalse("older than another page's", ledger.offer(answer("701", askedAtMs = 220L)))
        assertFalse("the same reading is not newer", ledger.offer(answer("701", askedAtMs = 250L)))

        val written = ledger.apply(listOf(row("701", completed = true, submittedAtMs = 1L)))
        assertFalse("the newest page answer stands", written[0].isCompleted)
    }

    @Test
    fun `a round asked for after a page answer takes its own status back`() {
        val ledger = AssignmentStatusLedger()
        assertTrue(ledger.offer(answer("701", askedAtMs = 100L)))
        ledger.recordRound(mapOf("701" to 300L))

        val written = ledger.apply(listOf(row("701")))

        assertFalse(written[0].isCompleted)
        assertFalse("and a page answer older than the round's request is dropped", ledger.offer(answer("701", askedAtMs = 200L)))
    }

    @Test
    fun `a round's later request does not move an assignment's time back`() {
        val ledger = AssignmentStatusLedger()
        ledger.recordRound(mapOf("701" to 300L))
        ledger.recordRound(mapOf("701" to 100L))

        assertFalse(ledger.offer(answer("701", askedAtMs = 200L)))
    }

    @Test
    fun `a sign-out forgets every time`() {
        val ledger = AssignmentStatusLedger()
        ledger.recordRound(mapOf("701" to 300L))
        assertTrue(ledger.offer(answer("702", askedAtMs = 300L)))

        ledger.clear()

        assertTrue(ledger.offer(answer("701", askedAtMs = 100L)))
        assertFalse("the departing account's page answer is not applied to the next account's row",
            ledger.apply(listOf(row("702"))).single().isCompleted)
    }
}
