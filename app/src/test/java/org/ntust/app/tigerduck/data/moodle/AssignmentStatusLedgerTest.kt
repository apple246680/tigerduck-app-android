package org.ntust.app.tigerduck.data.moodle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.model.Assignment
import java.util.Date

/**
 * Each assignment keeps the newest answer accepted for it, so an answer asked for before it, a
 * round's or a page's, never overwrites it: a round that asked before a page and writes after it,
 * even after a later round wrote, cannot take the newer status back.
 */
class AssignmentStatusLedgerTest {

    private fun row(id: String, completed: Boolean = false, submittedAtMs: Long? = null) = Assignment(
        assignmentId = id, courseNo = "CS3005301", courseName = "DS", title = "A$id", dueDate = Date(1_790_600_000_000L),
        isCompleted = completed, submittedAt = submittedAtMs?.let(::Date),
    )

    private fun answer(id: String, askedAtMs: Long, submitted: Boolean = true, submittedAtMs: Long? = 1_790_500_000_000L) =
        SubmissionStatusAnswer(id, submitted, submittedAtMs, askedAtMs)

    @Test
    fun `a page answer newer than the round's own request replaces the round's status at the write`() {
        val ledger = AssignmentStatusLedger()
        assertTrue(ledger.offer(answer("701", askedAtMs = 150L)))

        val written = ledger.reconcile(listOf(row("701"), row("702")), mapOf("701" to 100L, "702" to 100L))

        assertTrue(written[0].isCompleted)
        assertEquals(Date(1_790_500_000_000L), written[0].submittedAt)
        assertFalse("an assignment with no page answer keeps the round's status", written[1].isCompleted)
    }

    @Test
    fun `a round that asked before a page and writes after a later round keeps the newest status`() {
        val ledger = AssignmentStatusLedger()
        // Round 1 asked at 100 and found nothing handed in; its screen writes last, once its
        // course lookups are done. Meanwhile the page asked at 150 and round 2 at 200, both of
        // which found the submission.
        assertTrue(ledger.offer(answer("701", askedAtMs = 150L)))
        val second = ledger.reconcile(listOf(row("701", completed = true, submittedAtMs = 1_790_500_000_000L)), mapOf("701" to 200L))
        assertTrue(second.single().isCompleted)

        val first = ledger.reconcile(listOf(row("701")), mapOf("701" to 100L))

        assertTrue("round 1's older answer does not take the status back", first.single().isCompleted)
        assertEquals(Date(1_790_500_000_000L), first.single().submittedAt)
    }

    @Test
    fun `a round's status is accepted at its write, and a page answer asked for before it is dropped`() {
        val ledger = AssignmentStatusLedger()
        ledger.reconcile(listOf(row("701")), mapOf("701" to 200L))

        assertFalse("older than the round's request", ledger.offer(answer("701", askedAtMs = 150L)))
        assertFalse("the same reading is not newer", ledger.offer(answer("701", askedAtMs = 200L)))
        assertTrue(ledger.offer(answer("701", askedAtMs = 250L, submitted = false, submittedAtMs = null)))
        assertFalse("older than another page's", ledger.offer(answer("701", askedAtMs = 220L)))

        val written = ledger.reconcile(listOf(row("701", completed = true, submittedAtMs = 1L)), mapOf("701" to 230L))
        assertFalse("the newest page answer stands over a round asked for before it", written.single().isCompleted)
    }

    @Test
    fun `an assignment a round did not ask about takes a page answer, and records no time of its own`() {
        val ledger = AssignmentStatusLedger()
        assertTrue(ledger.offer(answer("701", askedAtMs = 150L)))

        // The round kept 701 from the cache, confirmed or closed, so it has no request for it.
        val written = ledger.reconcile(listOf(row("701")), askedAtMs = emptyMap())

        assertTrue(written.single().isCompleted)
        assertTrue("nothing stands in the way of the next page answer", ledger.offer(answer("701", askedAtMs = 160L)))
    }

    @Test
    fun `a sign-out forgets every answer`() {
        val ledger = AssignmentStatusLedger()
        ledger.reconcile(listOf(row("701")), mapOf("701" to 300L))
        assertTrue(ledger.offer(answer("702", askedAtMs = 300L)))

        ledger.clear()

        assertTrue(ledger.offer(answer("701", askedAtMs = 100L)))
        assertFalse(
            "the departing account's page answer is not applied to the next account's row",
            ledger.reconcile(listOf(row("702")), mapOf("702" to 50L)).single().isCompleted,
        )
    }
}
