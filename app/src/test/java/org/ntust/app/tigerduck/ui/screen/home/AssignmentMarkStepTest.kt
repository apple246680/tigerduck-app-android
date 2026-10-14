package org.ntust.app.tigerduck.ui.screen.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.ui.screen.home.AssignmentOverrideReconciler.STATUS_IGNORED
import java.io.IOException

/**
 * Home's assignment-mark step ([AssignmentMarkStep]): with the Assignments
 * sync switch off a full sync leaves this phone's done and ignored marks
 * alone, as on iOS (tigerduck-app-android#188, #209), and the first-sync
 * upload runs once per account (#155).
 */
class AssignmentMarkStepTest {

    private var switchOn = true
    private var pending = emptySet<String>()
    private var syncedBefore = false
    private var ignored = emptySet<String>()
    private var completed = emptySet<String>()
    private var writes = 0
    private val sent = mutableListOf<Pair<Int, String>>()
    private var send: suspend (Int, String) -> Unit = { id, status -> sent += id to status }

    /** Runs as the step reads this phone's completed marks, for a switch flipped mid-sync. */
    private var onRead: () -> Unit = {}

    private val step = AssignmentMarkStep(
        switchOn = { switchOn },
        pendingConflicts = { pending },
        syncedBefore = { syncedBefore },
        store = object : AssignmentMarkStep.MarkStore {
            override suspend fun ignored() = ignored
            override suspend fun completed() = completed.also { onRead() }
            override suspend fun replace(ignored: Set<String>, completed: Set<String>) {
                writes++
                this@AssignmentMarkStepTest.ignored = ignored
                this@AssignmentMarkStepTest.completed = completed
            }
        },
        send = { id, status -> send(id, status) },
    )

    private val state = HomeSyncState(
        ignoredAssignmentIds = MutableStateFlow(emptySet()),
        markedCompletedIds = MutableStateFlow(emptySet()),
        ignoredCourseKeys = MutableStateFlow(emptySet()),
        allAssignments = MutableStateFlow(emptyList()),
        allCourses = MutableStateFlow(emptyList()),
        conflicts = MutableStateFlow(emptyList()),
        pendingOverrides = mutableSetOf(),
    )

    private fun server(ignored: Set<String> = emptySet(), completed: Set<String> = emptySet()) = BackendSyncResult(
        assignments = emptyList(),
        ignoredIds = ignored,
        completedIds = completed,
        courseOverrides = emptyList(),
        serverCourseNos = emptySet(),
        currentRevision = 1,
    )

    @Test
    fun `with the switch off the server's marks are not taken`() = runTest {
        switchOn = false

        val synced = step.pull(state, server(ignored = setOf("1")), syncsMarks = false)

        assertFalse(synced)
        assertEquals(0, writes)
        assertEquals(emptySet<String>(), ignored)
    }

    @Test
    fun `with the switch off this phone's marks are not sent up as a first sync`() = runTest {
        switchOn = false
        completed = setOf("2")

        step.pull(state, server(), syncsMarks = false)

        assertEquals(emptyList<Pair<Int, String>>(), sent)
    }

    @Test
    fun `a switch turned off while the sync ran leaves the marks alone`() = runTest {
        onRead = { switchOn = false }

        val synced = step.pull(state, server(ignored = setOf("1")), syncsMarks = true)

        assertFalse(synced)
        assertEquals(0, writes)
    }

    @Test
    fun `the assignments re-enable check holds the marks`() = runTest {
        pending = setOf("assignments")

        assertFalse(step.pull(state, server(ignored = setOf("1")), syncsMarks = true))
        assertEquals(0, writes)
    }

    @Test
    fun `the account's first sync here sends this phone's marks up instead of taking the empty server`() = runTest {
        ignored = setOf("1")

        val synced = step.pull(state, server(), syncsMarks = true)

        assertTrue(synced)
        assertEquals(listOf(1 to STATUS_IGNORED), sent)
        assertEquals(setOf("1"), ignored)
    }

    @Test
    fun `a first sync with a mark that did not land is not counted`() = runTest {
        ignored = setOf("1")
        send = { _, _ -> throw IOException("offline") }

        assertFalse(step.pull(state, server(), syncsMarks = true))
    }

    @Test
    fun `after the first sync an empty server clears a stale copy instead of taking it back up`() = runTest {
        syncedBefore = true
        ignored = setOf("1")

        val synced = step.pull(state, server(), syncsMarks = true)

        assertTrue(synced)
        assertEquals(emptyList<Pair<Int, String>>(), sent)
        assertEquals(emptySet<String>(), ignored)
        assertEquals(emptySet<String>(), state.ignoredAssignmentIds.value)
    }

    @Test
    fun `a real disagreement is kept at this phone's value and asked about`() = runTest {
        syncedBefore = true
        completed = setOf("1")
        val snapshot = server(ignored = setOf("1"))

        step.pull(state, snapshot, syncsMarks = true)

        assertEquals(setOf("1"), completed)
        assertEquals(listOf("1"), state.conflicts.value.map { it.id })
        assertNotNull(state.pendingResult)
    }

    @Test
    fun `keeping the server's marks after the switch went off writes nothing`() = runTest {
        switchOn = false
        completed = setOf("1")

        step.keepServer(state, server(ignored = setOf("1")))

        assertEquals(0, writes)
        assertEquals(setOf("1"), completed)
    }

    @Test
    fun `keeping the server's marks takes them`() = runTest {
        completed = setOf("1")

        step.keepServer(state, server(ignored = setOf("1")))

        assertEquals(setOf("1"), ignored)
        assertEquals(emptySet<String>(), completed)
    }
}
