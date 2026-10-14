package org.ntust.app.tigerduck.ui.screen.settings

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.ui.screen.home.AssignmentOverrideReconciler.STATUS_COMPLETED
import org.ntust.app.tigerduck.ui.screen.home.AssignmentOverrideReconciler.STATUS_IGNORED
import org.ntust.app.tigerduck.ui.screen.home.AssignmentOverrideReconciler.STATUS_NONE

class KeptMarksTest {

    @Test
    fun `each mark goes out as this phone has it when its request is made`() = runTest {
        var version = 0
        var marks = mapOf("1" to STATUS_IGNORED, "2" to STATUS_NONE, "3" to STATUS_COMPLETED)
        val sent = mutableListOf<Pair<Int, String>>()

        sendKeptMarks(
            marksVersion = { version },
            marksNow = { marks },
            send = { id, status ->
                sent += id to status
                // Taps on Home while the first request is out: "2" marked
                // done, "3" unmarked.
                if (id == 1) {
                    marks = mapOf("1" to STATUS_IGNORED, "2" to STATUS_COMPLETED)
                    version++
                }
            },
            keep = { _, _ -> },
        )

        assertEquals(listOf(1 to STATUS_IGNORED, 2 to STATUS_COMPLETED), sent)
    }

    @Test
    fun `the marks are read again only when they have changed`() = runTest {
        var version = 0
        var reads = 0
        var requests = 0

        sendKeptMarks(
            marksVersion = { version },
            marksNow = { reads++; (1..200).associate { it.toString() to STATUS_IGNORED } },
            send = { id, _ ->
                requests++
                if (id == 100) version++
            },
            keep = { _, _ -> },
        )

        assertEquals(200, requests)
        assertEquals(2, reads)
    }

    @Test
    fun `a failed request does not stop the others`() = runTest {
        val sent = mutableListOf<Int>()

        sendKeptMarks(
            marksVersion = { 0 },
            marksNow = { mapOf("1" to STATUS_IGNORED, "x" to STATUS_IGNORED, "2" to STATUS_NONE) },
            send = { id, _ ->
                sent += id
                if (id == 1) error("HTTP 500")
            },
            keep = { _, _ -> },
        )

        assertEquals(listOf(1, 2), sent)
    }

    @Test
    fun `a mark that fails to go up is kept to go up later`() = runTest {
        val kept = mutableListOf<Pair<String, String>>()

        sendKeptMarks(
            marksVersion = { 0 },
            marksNow = { mapOf("1" to STATUS_IGNORED, "2" to STATUS_NONE, "3" to STATUS_COMPLETED) },
            send = { id, _ -> if (id != 3) throw java.io.IOException("offline") },
            keep = { id, status -> kept += id to status },
        )

        // Lost otherwise: once the hold goes, the next sync takes the server,
        // which never got them, as the truth.
        assertEquals(listOf("1" to STATUS_IGNORED, "2" to STATUS_NONE), kept)
    }
}
