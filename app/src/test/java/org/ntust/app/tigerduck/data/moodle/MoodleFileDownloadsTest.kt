package org.ntust.app.tigerduck.data.moodle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The downloaded Moodle files' store on its own: a file's state, and what is left on the device. */
@OptIn(ExperimentalCoroutinesApi::class)
class MoodleFileDownloadsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val location get() = File(tmp.root, "acct/5/m1/ab/1/notes.pdf")

    @Test
    fun `a download stopped and tapped again keeps showing the new one's progress after the old one ends`() =
        runTest(UnconfinedTestDispatcher()) {
            val downloads = MoodleFileDownloads(root = { tmp.root }, scope = backgroundScope, io = Dispatchers.Unconfined)
            val firstStarted = CompletableDeferred<Unit>()
            val firstEnds = CompletableDeferred<Unit>()
            val first = async {
                downloads.download(location, fetch = { _, _ ->
                    firstStarted.complete(Unit)
                    try {
                        CompletableDeferred<Unit>().await()
                    } catch (e: CancellationException) {
                        // A stopped request can take a moment to wind down.
                        withContext(NonCancellable) { firstEnds.await() }
                        throw e
                    }
                }, isCurrent = { true })
            }
            firstStarted.await()
            downloads.cancel(location)

            val second = async {
                downloads.download(location, fetch = { _, progress ->
                    progress(0.4f)
                    CompletableDeferred<Unit>().await()
                }, isCurrent = { true })
            }
            runCurrent()
            firstEnds.complete(Unit)
            runCurrent()

            assertNull(first.await())
            assertEquals(MoodleDownloadState.Downloading(0.4f), downloads.state(location))
            second.cancel()
        }
}
