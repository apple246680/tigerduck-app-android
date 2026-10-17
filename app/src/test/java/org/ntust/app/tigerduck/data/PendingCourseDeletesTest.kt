package org.ntust.app.tigerduck.data

import android.app.Application
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.push.PushApiException
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * A course deleted here whose backend DELETE failed ([PendingCourseDeletes]), over real files.
 * It used to go once: a course taken back out of Add course writes no tombstone here, so a
 * failed DELETE left its row on the server for the next sync to merge back as a hand-added
 * course (and a regular delete's tombstone did not help: the reconcile un-hides a course the
 * server still lists). Now the delete waits on disk, every full sync sends it again first,
 * and the reconcile keeps the course out until it lands.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PendingCourseDeletesTest {

    private lateinit var cache: DataCache
    private lateinit var deletes: PendingCourseDeletes

    private val sent = mutableListOf<String>()
    private var answer: suspend (String) -> Unit = {}

    private val term = "1151"
    private val typed = "MA1010002"

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context)))
        deletes = PendingCourseDeletes(cache) { courseKey ->
            sent += courseKey
            answer(courseKey)
        }
    }

    @Test
    fun `a delete that fails is kept, and the next sync sends it again with the same key`() = runTest {
        answer = { throw IOException("offline") }
        deletes.send(term, typed)
        assertEquals(listOf("client:1151:MA1010002"), sent)
        assertEquals(mapOf(term to listOf(typed)), cache.loadPendingCourseDeletes())
        // What the reconcile keeps out of the term meanwhile.
        assertEquals(setOf(typed), deletes.waitingIn(term))
        assertEquals(emptySet<String>(), deletes.waitingIn("1142"))

        answer = { throw PushApiException("deleteCourse failed: HTTP 500") }
        deletes.retry()
        assertEquals(mapOf(term to listOf(typed)), cache.loadPendingCourseDeletes())

        answer = {}
        deletes.retry()
        assertEquals(listOf("client:1151:MA1010002", "client:1151:MA1010002", "client:1151:MA1010002"), sent)
        assertEquals(emptyMap<String, List<String>>(), cache.loadPendingCourseDeletes())
        assertEquals(emptySet<String>(), deletes.waitingIn(term))
    }

    @Test
    fun `a delete that lands is not kept`() = runTest {
        deletes.send(term, typed)
        assertEquals(listOf("client:1151:MA1010002"), sent)
        assertEquals(emptyMap<String, List<String>>(), cache.loadPendingCourseDeletes())
    }

    /** Adding the course back, or resetting its term, drops the delete still queued for it. */
    @Test
    fun `a course added back or a term reset lets its queued delete go`() = runTest {
        answer = { throw IOException("offline") }
        deletes.send(term, typed)
        deletes.send(term, "PE1010003")
        deletes.send("1142", typed)

        deletes.forget(term, typed)
        assertEquals(mapOf(term to listOf("PE1010003"), "1142" to listOf(typed)), cache.loadPendingCourseDeletes())

        deletes.forget(term)
        assertEquals(mapOf("1142" to listOf(typed)), cache.loadPendingCourseDeletes())

        deletes.retry()
        assertEquals(listOf("client:1142:MA1010002"), sent.drop(3))
    }

    /** Account-scoped: a sign-out's wipe takes the queue with the rest of the user's data. */
    @Test
    fun `a sign-out leaves no delete to send as the next account`() = runTest {
        answer = { throw IOException("offline") }
        deletes.send(term, typed)

        cache.clearAllUserData()

        assertEquals(emptyMap<String, List<String>>(), cache.loadPendingCourseDeletes())
        deletes.retry()
        assertEquals(1, sent.size)
    }
}
