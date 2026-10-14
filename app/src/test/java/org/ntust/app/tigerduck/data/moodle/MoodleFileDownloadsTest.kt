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

    @Test
    fun `a download stopped while its partial file is being made leaves nothing behind`() = runTest {
        // The disk's work waits here until the test runs it, so the stop can land in between.
        val disk = ArrayDeque<Runnable>()
        val io = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                disk += block
            }
        }
        val downloads = MoodleFileDownloads(root = { tmp.root }, scope = backgroundScope, io = io)
        var fetched = false
        val opening = async { downloads.download(location, fetch = { _, _ -> fetched = true }, isCurrent = { true }) }
        runCurrent()
        disk.removeFirst().run() // is there a copy already
        runCurrent()
        disk.removeFirst().run() // the partial file is made, and the stop lands before its maker hears
        assertTrue(location.parentFile!!.listFiles()!!.any { it.name.endsWith(".part") })

        downloads.cancel(location)
        runCurrent()
        while (disk.isNotEmpty()) {
            disk.removeFirst().run()
            runCurrent()
        }

        assertNull(opening.await())
        assertTrue(!fetched)
        assertTrue(tmp.root.walkTopDown().none { it.isFile })
    }

    @Test
    fun `partial files a download left when the app was ended are swept, and do not count`() = runTest(UnconfinedTestDispatcher()) {
        val downloads = MoodleFileDownloads(root = { tmp.root }, scope = backgroundScope, io = Dispatchers.Unconfined)
        val course = File(tmp.root, "acct/5")
        val stale = File(course, "m1/ab/1/.dl123.part").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(700))
        }
        val elsewhere = File(tmp.root, "acct/6/m2/cd/1/.dl456.part").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(300))
        }
        val kept = File(course, "m1/ef/1/notes.pdf").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(100))
        }

        downloads.index(course)
        assertTrue(!stale.exists())
        assertEquals(setOf(kept.path), downloads.snapshot.value.copies)

        assertEquals("the settings' measure sweeps every course's", 100L, downloads.measure())
        assertTrue(!elsewhere.exists())
    }

    @Test
    fun `the partial file of a download on its way is not swept`() = runTest(UnconfinedTestDispatcher()) {
        val downloads = MoodleFileDownloads(root = { tmp.root }, scope = backgroundScope, io = Dispatchers.Unconfined)
        val started = CompletableDeferred<Unit>()
        val opening = async {
            downloads.download(location, fetch = { target, _ ->
                target.writeText("half")
                started.complete(Unit)
                CompletableDeferred<Unit>().await()
            }, isCurrent = { true })
        }
        started.await()

        downloads.index(File(tmp.root, "acct/5"))
        downloads.measure()

        assertTrue(location.parentFile!!.listFiles()!!.any { it.name.endsWith(".part") })
        opening.cancel()
    }
}
