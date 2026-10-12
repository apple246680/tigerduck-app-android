package org.ntust.app.tigerduck.data

import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.push.CourseOverrideResult
import org.ntust.app.tigerduck.push.ServerCourse
import org.ntust.app.tigerduck.shared.Course
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Course ignores over real files, with the backend faked. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CourseIgnoreSyncTest {

    private data class Sent(val moodleId: String, val ignore: Boolean?, val moodleCourseId: Int?)

    private lateinit var cache: DataCache
    private lateinit var sync: CourseIgnoreSync

    private val sent = mutableListOf<Sent>()
    private var answer: suspend (Sent) -> Boolean = { true }
    private var queues = true
    private var sends = true
    private var now = 1_000L
    private var effects = 0

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context)))
        sync = CourseIgnoreSync(
            dataCache = cache,
            sender = CourseIgnoreSender { moodleId, ignore, moodleCourseId ->
                val request = Sent(moodleId, ignore, moodleCourseId)
                sent += request
                answer(request)
            },
            effects = CourseIgnoreEffects { effects++ },
            queues = { queues },
            sends = { sends },
            clock = { now },
        )
    }

    private suspend fun setIgnored(courseNo: String, ignore: Boolean = true) =
        sync.setIgnored("1141", courseNo, "1141$courseNo", null, ignore)

    private suspend fun waiting() = cache.loadPendingCourseIgnores().map { it.key to it.ignore }

    private fun snapshot(fetchedAtMs: Long, vararg ignores: Pair<String, Boolean>) = BackendSyncResult(
        assignments = emptyList(),
        ignoredIds = emptySet(),
        completedIds = emptySet(),
        courseOverrides = ignores.map { (courseNo, ignore) ->
            CourseOverrideResult(
                moodleCourseId = "1141$courseNo",
                courseNo = courseNo,
                colorHex = null,
                semester = "1141",
                ignoreAssignments = ignore,
            )
        },
        serverCourseNos = setOf("AAA", "BBB"),
        serverCourses = listOf(
            ServerCourse(courseNo = "AAA", courseName = "A", semester = "1141"),
            ServerCourse(courseNo = "BBB", courseName = "B", semester = "1141"),
        ),
        currentRevision = 0,
        fetchedAtMs = fetchedAtMs,
    )

    @Test
    fun `a change the backend confirms is not kept waiting`() = runTest {
        setIgnored("AAA")

        assertEquals(setOf("1141:AAA"), cache.loadIgnoredCourses())
        assertEquals(listOf(Sent("1141AAA", true, null)), sent)
        assertEquals(emptyList<Any>(), waiting())
        assertEquals("reminders and the Live Update are refreshed", 1, effects)
    }

    @Test
    fun `a change that fails waits and goes out first on the next sync`() = runTest {
        answer = { throw java.io.IOException("offline") }
        setIgnored("AAA")
        assertEquals(listOf("1141:AAA" to true), waiting())

        answer = { true }
        sync.retryPending()

        assertEquals(2, sent.size)
        assertEquals(emptyList<Any>(), waiting())
    }

    @Test
    fun `an answer without the field, from an older backend, keeps the change waiting`() = runTest {
        answer = { false }

        setIgnored("AAA")

        assertEquals(listOf("1141:AAA" to true), waiting())
    }

    @Test
    fun `a change made while the last one is on the wire outlives its confirmation`() = runTest {
        val onTheWire = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        answer = { request ->
            if (request.ignore == true) {
                onTheWire.complete(Unit)
                release.await()
                true
            } else {
                false
            }
        }
        val first = launch { setIgnored("AAA", ignore = true) }
        onTheWire.await()

        now = 2_000L
        setIgnored("AAA", ignore = false)
        release.complete(Unit)
        first.join()

        assertEquals(emptySet<String>(), cache.loadIgnoredCourses())
        assertEquals(listOf("1141:AAA" to false), waiting())
    }

    @Test
    fun `with assignment sync off a change waits unsent until it is on`() = runTest {
        sends = false
        setIgnored("AAA")
        sync.retryPending()
        assertEquals(emptyList<Sent>(), sent)
        assertEquals(listOf("1141:AAA" to true), waiting())

        sends = true
        sync.retryPending()

        assertEquals(listOf(Sent("1141AAA", true, null)), sent)
        assertEquals(emptyList<Any>(), waiting())
    }

    @Test
    fun `with cloud sync off a change stays on this device`() = runTest {
        queues = false
        sends = false

        setIgnored("AAA")

        assertEquals(setOf("1141:AAA"), cache.loadIgnoredCourses())
        assertEquals(emptyList<Any>(), waiting())
    }

    @Test
    fun `a snapshot fetched before a change does not undo it`() = runTest {
        setIgnored("AAA")

        val applied = sync.applySnapshot(snapshot(fetchedAtMs = 500, "AAA" to false))

        assertNull(applied)
        assertEquals(setOf("1141:AAA"), cache.loadIgnoredCourses())
    }

    @Test
    fun `a later snapshot is taken as the backend has it`() = runTest {
        setIgnored("AAA")
        val effectsBefore = effects

        val applied = sync.applySnapshot(snapshot(fetchedAtMs = 2_000, "AAA" to false, "BBB" to true))

        assertEquals(setOf("1141:BBB"), applied)
        assertEquals(setOf("1141:BBB"), cache.loadIgnoredCourses())
        assertEquals(effectsBefore + 1, effects)
    }

    @Test
    fun `a waiting change keeps its value through any snapshot`() = runTest {
        answer = { false }
        setIgnored("AAA")

        sync.applySnapshot(snapshot(fetchedAtMs = 5_000, "AAA" to false))

        assertEquals(setOf("1141:AAA"), cache.loadIgnoredCourses())
    }

    @Test
    fun `deleting a course forgets its setting and its waiting change`() = runTest {
        answer = { false }
        setIgnored("AAA")

        sync.forget { it == "1141:AAA" }

        assertEquals(emptySet<String>(), cache.loadIgnoredCourses())
        assertEquals(emptyList<Any>(), waiting())
    }

    @Test
    fun `taking the backend's set drops what was waiting`() = runTest {
        answer = { false }
        setIgnored("AAA")

        sync.adoptServer(snapshot(fetchedAtMs = 5_000, "BBB" to true))

        assertEquals(setOf("1141:BBB"), cache.loadIgnoredCourses())
        assertEquals(emptyList<Any>(), waiting())
    }

    @Test
    fun `keeping this device's set sends it over the backend's`() = runTest {
        cache.saveIgnoredCourses(setOf("1141:AAA"))

        sync.pushLocal(snapshot(fetchedAtMs = 5_000, "BBB" to true), onlyIgnored = false)

        assertEquals(
            setOf(Sent("1141AAA", true, null), Sent("1141BBB", false, null)),
            sent.toSet(),
        )
        assertEquals(emptyList<Any>(), waiting())
    }

    @Test
    fun `a backend row without the numeric Moodle id gets it from this device`() = runTest {
        cache.saveCourses(
            listOf(
                Course(
                    courseNo = "AAA",
                    courseName = "A",
                    moodleIdNumber = "1141AAA",
                    moodleNumericCourseId = 77,
                )
            ),
            "1141",
        )

        sync.applySnapshot(snapshot(fetchedAtMs = 5_000, "AAA" to true))

        assertEquals(listOf(Sent("1141AAA", null, 77)), sent)
    }
}
