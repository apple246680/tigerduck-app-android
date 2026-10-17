package org.ntust.app.tigerduck.ui.screen.classtable

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.CourseTombstoneKeys
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

    /** The snapshot the fold is taken over, with the term's cache and the tombstone file each behind a gate. */
    private class Snapshot {
        val edits = ClassTableTombstoneEdits()
        val cacheGate = CompletableDeferred<Unit>()
        val fileGate = CompletableDeferred<Unit>()
        var cached = listOf<Course>()
        var file = emptySet<String>()
        /** The list on screen while it is this term's, else null. */
        var shown: List<Course>? = null

        /** A delete as the view model makes one: off the list at once, the tombstone's write after. */
        fun delete(courseNo: String, written: Boolean) {
            shown = shown?.filter { it.courseNo != courseNo }
            val id = edits.edit { CourseTombstoneKeys.hide(courseNo, "1151", it) }
            if (!written) return
            file = CourseTombstoneKeys.hide(courseNo, "1151", file)
            edits.saved(id)
        }
    }

    private fun TestScope.foldLatest(answers: ClassTableTermFetch.Answers, snapshot: Snapshot): () -> List<Course> {
        var out: List<Course>? = null
        launch {
            out = fetch.foldLatest(
                answers, "1151",
                cachedCourses = { snapshot.cacheGate.await(); snapshot.cached },
                storedTombstones = { snapshot.fileGate.await(); snapshot.file },
                shownCourses = { snapshot.shown },
                withPendingEdits = snapshot.edits::over,
                names = { emptyMap() },
                locale = "zh",
            )
        }
        return { out!! }
    }

    @Test
    fun `a delete written while the term's cache is read stays deleted`() = runTest {
        // The term was not on screen when the commit began, so its list comes from the cache.
        // While that read runs, the term comes on screen, GONE is deleted, and the tombstone's
        // write lands, retiring the pending edit: only the file knows the delete now, so the
        // file has to be read after the cache, and the list chosen once both are in.
        gate.complete(Unit)
        val answers = lookUp(listOf("A", "GONE"), before = listOf(course("A"), course("GONE")))
        // The file answers at once; only the cache read is slow.
        val snapshot = Snapshot().apply {
            cached = listOf(course("A"), course("GONE"))
            fileGate.complete(Unit)
        }
        val out = foldLatest(answers, snapshot)
        advanceUntilIdle()
        snapshot.shown = listOf(course("A"), course("GONE"))
        snapshot.delete("GONE", written = true)
        snapshot.cacheGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("A"), out().map { it.courseNo })
    }

    @Test
    fun `a delete not yet written while the tombstones are read stays deleted`() = runTest {
        gate.complete(Unit)
        val answers = lookUp(listOf("A", "GONE"), before = listOf(course("A"), course("GONE")))
        val snapshot = Snapshot().apply {
            shown = listOf(course("A"), course("GONE"))
            cacheGate.complete(Unit)
        }
        val out = foldLatest(answers, snapshot)
        advanceUntilIdle()
        // Tapped while the file is read: the pending edit is what carries it.
        snapshot.delete("GONE", written = false)
        snapshot.fileGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("A"), out().map { it.courseNo })
    }
}
