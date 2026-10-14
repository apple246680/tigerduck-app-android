package org.ntust.app.tigerduck.data

import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Marks tapped here whose PATCH failed ([UnsentMarks]), over real files. A
 * failed PATCH used to be only logged: once the account's marks had been
 * through a sync, the next one took the server — which never got the mark —
 * as the truth and wiped it here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UnsentMarksTest {

    private lateinit var cache: DataCache
    private lateinit var marks: UnsentMarks

    private val sent = mutableListOf<Pair<Int, String>>()
    private var answer: suspend (Int, String) -> Unit = { _, _ -> }
    private var sends = true

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context)))
        marks = newProcess()
    }

    /** What a new process builds over the same files. */
    private fun newProcess() = UnsentMarks(
        dataCache = cache,
        sender = MarkSender { id, status ->
            sent += id to status
            answer(id, status)
        },
        sends = { sends },
    )

    @Test
    fun `a tap whose PATCH fails is kept, and the next sync sends it and lets it go`() = runTest {
        answer = { _, _ -> throw IOException("offline") }
        marks.send("7", "locally_completed")
        assertEquals(setOf("7"), marks.ids())

        answer = { _, _ -> }
        marks.retry()

        assertEquals(listOf(7 to "locally_completed", 7 to "locally_completed"), sent)
        assertEquals(emptySet<String>(), marks.ids())
    }

    @Test
    fun `a tap that lands is not kept`() = runTest {
        marks.send("7", "ignored")

        assertEquals(listOf(7 to "ignored"), sent)
        assertEquals(emptySet<String>(), marks.ids())
    }

    @Test
    fun `an older tap landing late does not let a newer one go`() = runTest {
        val first = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        answer = { _, status ->
            if (status == "ignored") {
                first.complete(Unit)
                release.await()
            } else {
                throw IOException("offline")
            }
        }

        val older = launch { marks.send("7", "ignored") }
        first.await()
        val newer = launch { marks.send("7", "none") }
        testScheduler.runCurrent()
        release.complete(Unit)
        older.join()
        newer.join()

        assertEquals(mapOf("7" to "none"), cache.loadUnsentMarks())
    }

    @Test
    fun `nothing is sent or kept with mark sync off, and what waits stays until it is on`() = runTest {
        answer = { _, _ -> throw IOException("offline") }
        marks.send("7", "ignored")
        sends = false

        marks.send("8", "ignored")
        marks.retry()

        assertEquals(listOf(7 to "ignored"), sent)
        assertEquals(setOf("7"), marks.ids())
    }

    @Test
    fun `what waits survives a new process`() = runTest {
        answer = { _, _ -> throw IOException("offline") }
        marks.send("7", "ignored")

        assertEquals(setOf("7"), newProcess().ids())
    }

    @Test
    fun `taking the server's marks drops what waits`() = runTest {
        answer = { _, _ -> throw IOException("offline") }
        marks.send("7", "ignored")

        marks.forget()

        assertEquals(emptySet<String>(), marks.ids())
    }

    @Test
    fun `signing out drops what waits`() = runTest {
        answer = { _, _ -> throw IOException("offline") }
        marks.send("7", "ignored")

        cache.clearAllUserData()

        assertEquals(emptySet<String>(), marks.ids())
    }
}
