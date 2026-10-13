package org.ntust.app.tigerduck.ui.screen.settings

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.ui.screen.home.AssignmentOverrideReconciler.STATUS_COMPLETED
import org.ntust.app.tigerduck.ui.screen.home.AssignmentOverrideReconciler.STATUS_IGNORED
import org.ntust.app.tigerduck.ui.screen.home.AssignmentOverrideReconciler.STATUS_NONE

class KeptMarksTest {

    @Test
    fun `each mark ends as this phone has it after taps made while they go out`() = runTest {
        var marks = mapOf("1" to STATUS_IGNORED, "2" to STATUS_NONE, "3" to STATUS_COMPLETED)
        val sent = mutableListOf<Pair<Int, String>>()

        sendKeptMarks(
            marksNow = { marks },
            send = { id, status ->
                sent += id to status
                // Taps on Home while the first request is out: "2" marked
                // done, "3" unmarked, and each tap has sent its own PATCH.
                if (id == 1) marks = mapOf("1" to STATUS_IGNORED, "2" to STATUS_COMPLETED)
            },
        )

        assertEquals(
            listOf(
                1 to STATUS_IGNORED, 2 to STATUS_NONE, 3 to STATUS_COMPLETED,
                2 to STATUS_COMPLETED, 3 to STATUS_NONE,
            ),
            sent,
        )
    }

    @Test
    fun `the marks are read again only until nothing has changed`() = runTest {
        val marks = (1..200).associate { it.toString() to STATUS_IGNORED }
        var reads = 0
        var requests = 0

        sendKeptMarks(
            marksNow = { reads++; marks },
            send = { _, _ -> requests++ },
        )

        assertEquals(200, requests)
        assertEquals(2, reads)
    }

    @Test
    fun `a failed request does not stop the others`() = runTest {
        val sent = mutableListOf<Int>()

        sendKeptMarks(
            marksNow = { mapOf("1" to STATUS_IGNORED, "x" to STATUS_IGNORED, "2" to STATUS_NONE) },
            send = { id, _ ->
                sent += id
                if (id == 1) error("HTTP 500")
            },
        )

        assertEquals(listOf(1, 2), sent)
    }
}
