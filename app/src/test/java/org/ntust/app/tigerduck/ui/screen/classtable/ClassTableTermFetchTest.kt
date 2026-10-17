package org.ntust.app.tigerduck.ui.screen.classtable

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course

/**
 * One term's fetch as far as its list goes. The lookups take seconds, and the user keeps
 * editing meanwhile: what they did while the requests ran has to be in what the fetch saves,
 * so the answers are folded onto the list, and the tombstones, as they stand once they land.
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

    private suspend fun lookUp(rosterNos: List<String>, before: List<Course>) =
        fetch.lookUp("1151", rosterNos, emptyMap(), before)

    private fun fold(answers: ClassTableTermFetch.Answers, latest: List<Course>, deleted: Set<String> = emptySet()) =
        fetch.fold(answers, latest, names = emptyMap(), locale = "zh", deletedNos = deleted)

    @Test
    fun `an edit and a delete made while the lookups run survive the fetch`() = runTest {
        val before = listOf(course("A"), manual("MINE", room = "T4-101"), manual("GONE"))
        var answers: ClassTableTermFetch.Answers? = null
        launch { answers = lookUp(listOf("A"), before) }
        advanceUntilIdle()
        // While the lookups run: A recoloured, GONE deleted, NEW added by hand. Each writes the
        // term's list, which the fetch reads again once the answers are in.
        val latest = listOf(course("A", color = "#FF0000"), manual("MINE", room = "T4-101"), manual("NEW"))
        gate.complete(Unit)
        advanceUntilIdle()

        val out = fold(answers!!, latest, deleted = setOf("GONE"))
        assertEquals(listOf("A", "MINE", "NEW"), out.map { it.courseNo })
        assertEquals("#FF0000", out[0].customColorHex)
        // MINE was looked up again and refreshed; NEW came too late for this round and is kept as typed.
        assertEquals("IB-202", out[1].classroom)
        assertTrue(out[1].isManual)
        assertEquals(manual("NEW"), out[2])
        assertEquals(setOf("A", "MINE", "GONE"), asked.toSet())
        assertTrue(answers!!.answered)
    }

    @Test
    fun `a roster course deleted while the lookups run stays deleted`() = runTest {
        // deleteCourse takes the row off the list at once and writes its tombstone after. The
        // answer for it is in, so only the tombstone keeps it out: the fold has to see the
        // delete's tombstone ahead of that write, together with the list the delete left.
        gate.complete(Unit)
        val answers = lookUp(listOf("A", "GONE"), before = listOf(course("A"), course("GONE")))
        val latest = listOf(course("A"))
        assertEquals(listOf("A"), fold(answers, latest, deleted = setOf("GONE")).map { it.courseNo })
        assertEquals(listOf("A", "GONE"), fold(answers, latest).map { it.courseNo })
    }

    @Test
    fun `with no roster, the hand-added rows are refreshed and the tombstones still applied`() = runTest {
        gate.complete(Unit)
        val latest = listOf(manual("MINE"), manual("GONE"))
        val answers = lookUp(emptyList(), before = latest)
        // GONE's delete is in the tombstones before its write of the list lands.
        val out = fold(answers, latest, deleted = setOf("GONE"))
        assertEquals(listOf("MINE"), out.map { it.courseNo })
        assertEquals("IB-202", out.single().classroom)
        assertTrue(out.single().isManual)
    }

    @Test
    fun `a failed query is reported, and a hand-added course the query does not answer keeps its snapshot`() = runTest {
        gate.complete(Unit)
        failing += "B"
        unanswered += "MINE"
        val latest = listOf(course("A"), manual("MINE", room = "T4-101"))
        val answers = lookUp(listOf("A", "B"), before = latest)
        assertFalse(answers.answered)
        val out = fold(answers, latest)
        assertEquals(listOf("A", "MINE"), out.map { it.courseNo })
        assertEquals("T4-101", out[1].classroom)
    }

    @Test
    fun `a term with nothing to look up, or nothing answered, is left as it is`() = runTest {
        gate.complete(Unit)
        assertTrue(lookUp(emptyList(), before = listOf(course("A"))).isEmpty)
        unanswered += listOf("A", "MINE")
        val answers = lookUp(listOf("A"), before = listOf(course("A"), manual("MINE")))
        assertTrue(answers.isEmpty)
        assertTrue(answers.answered)
    }
}
