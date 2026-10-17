package org.ntust.app.tigerduck.ui.screen.classtable

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course

/**
 * One term's fetch as far as its list goes. The lookups take seconds, and the user keeps
 * editing meanwhile: what they did while the requests ran has to be in what the fetch saves.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClassTableTermFetchTest {

    private val gate = CompletableDeferred<Unit>()
    private val asked = mutableListOf<String>()
    private val unanswered = mutableSetOf<String>()
    private val failing = mutableSetOf<String>()

    /** Every code answered with a fresh row in IB-202, but the unanswered and the failing ones. */
    private val fetch = ClassTableTermFetch { _, courseNo, _, onFailed ->
        asked += courseNo
        gate.await()
        when (courseNo) {
            in failing -> { onFailed(); null }
            in unanswered -> null
            else -> Course.fromSchedule(courseNo, "$courseNo now", classroom = "IB-202", schedule = mapOf(2 to listOf("5")))
        }
    }

    private fun course(no: String, color: String? = null) = Course(courseNo = no, courseName = no, customColorHex = color)
    private fun manual(no: String, room: String = "") =
        Course.fromSchedule(no, no, classroom = room, schedule = mapOf(1 to listOf("1")), isManual = true)

    private fun run(
        rosterNos: List<String>,
        latest: () -> List<Course>,
        deletedNos: () -> Set<String> = { emptySet() },
    ): ClassTableTermFetch.Outcome {
        lateinit var outcome: ClassTableTermFetch.Outcome
        runTest {
            launch {
                outcome = fetch.run(
                    "1151", rosterNos, emptyMap(),
                    latest = { latest() }, names = { emptyMap() }, locale = "zh", deletedNos = { deletedNos() },
                )
            }
            advanceUntilIdle()
            during()
            gate.complete(Unit)
            advanceUntilIdle()
        }
        return outcome
    }

    /** What happens while the lookups run. */
    private var during: () -> Unit = {}

    @Test
    fun `an edit and a delete made while the lookups run survive the fetch`() {
        var state = listOf(course("A"), manual("MINE", room = "T4-101"), manual("GONE"))
        var deleted = emptySet<String>()
        during = {
            // A recoloured, GONE deleted, NEW added by hand: each writes the term's list.
            state = listOf(course("A", color = "#FF0000"), manual("MINE", room = "T4-101"), manual("NEW"))
            deleted = setOf("GONE")
        }
        val outcome = run(rosterNos = listOf("A"), latest = { state }, deletedNos = { deleted })

        val out = outcome.courses!!
        assertEquals(listOf("A", "MINE", "NEW"), out.map { it.courseNo })
        assertEquals("#FF0000", out[0].customColorHex)
        // MINE was looked up again and refreshed; NEW came too late for this round and is kept as typed.
        assertEquals("IB-202", out[1].classroom)
        assertTrue(out[1].isManual)
        assertEquals(manual("NEW"), out[2])
        assertEquals(setOf("A", "MINE", "GONE"), asked.toSet())
        assertTrue(outcome.answered)
    }

    @Test
    fun `with no roster, the hand-added rows are refreshed and the tombstones still applied`() {
        // GONE's delete is in the tombstones before its write of the list lands.
        val outcome = run(
            rosterNos = emptyList(),
            latest = { listOf(manual("MINE"), manual("GONE")) },
            deletedNos = { setOf("GONE") },
        )
        val out = outcome.courses!!
        assertEquals(listOf("MINE"), out.map { it.courseNo })
        assertEquals("IB-202", out.single().classroom)
        assertTrue(out.single().isManual)
    }

    @Test
    fun `a failed query is reported, and a hand-added course the query does not answer keeps its snapshot`() {
        failing += "B"
        unanswered += "MINE"
        val outcome = run(rosterNos = listOf("A", "B"), latest = { listOf(course("A"), manual("MINE", room = "T4-101")) })
        assertFalse(outcome.answered)
        val out = outcome.courses!!
        assertEquals(listOf("A", "MINE"), out.map { it.courseNo })
        assertEquals("T4-101", out[1].classroom)
    }

    @Test
    fun `a term with nothing to look up, or nothing answered, is left as it is`() {
        assertNull(run(rosterNos = emptyList(), latest = { listOf(course("A")) }).courses)
        unanswered += listOf("A", "MINE")
        val outcome = run(rosterNos = listOf("A"), latest = { listOf(course("A"), manual("MINE")) })
        assertNull(outcome.courses)
        assertTrue(outcome.answered)
    }
}
