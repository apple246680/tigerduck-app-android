package org.ntust.app.tigerduck.ui.screen.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.Course
import java.io.IOException

/**
 * Use Local for courses ([keepLocalCourses]) against [FakeCourseServer],
 * which keeps the course rules of tigerduck-backend's
 * `server/routes/sync/courses.py` and `uploads.py`
 * (tigerduck-app/tigerduck-app-android#153). It used to reset every term at
 * once and upload the current term's cache: the reset erased every tombstone
 * and the next roster upload brought a course deleted here back everywhere.
 */
class KeepLocalCoursesTest {

    private inline fun <reified T : Throwable> assertThrown(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return
            throw e
        }
        throw AssertionError("expected ${T::class.simpleName}")
    }

    private fun roster(vararg courseNos: String) = courseNos.map { Course(courseNo = it, courseName = it) }

    /** What a refresh does after Use Local: upload the term's whole roster, hidden course included. */
    private suspend fun FakeCourseServer.uploadRoster(semester: String, vararg courseNos: String) =
        uploadCourses(roster(*courseNos), semester)

    @Test
    fun `a course deleted here stays deleted after Use Local and the next roster upload`() = runTest {
        // The delete never reached the server: course sync was off.
        val server = FakeCourseServer(rows = setOf("client:1151:CS1", "client:1151:CS2"))

        keepLocalCourses(listOf("1151" to roster("CS1")), hiding = setOf("1151:CS2"), backend = server)
        assertEquals(setOf("client:1151:CS1"), server.rows)

        server.uploadRoster("1151", "CS1", "CS2")
        assertEquals(setOf("client:1151:CS1"), server.rows)
    }

    @Test
    fun `a course whose delete did reach the server stays deleted too`() = runTest {
        val server = FakeCourseServer(
            rows = setOf("client:1151:CS1"),
            tombstones = mutableMapOf("client:1151:CS2" to false),
        )

        keepLocalCourses(listOf("1151" to roster("CS1")), hiding = setOf("1151:CS2"), backend = server)

        server.uploadRoster("1151", "CS1", "CS2")
        assertEquals(setOf("client:1151:CS1"), server.rows)
    }

    @Test
    fun `a hidden course still in the cache stays deleted`() = runTest {
        val server = FakeCourseServer(rows = setOf("client:1151:CS1", "client:1151:CS2"))

        keepLocalCourses(listOf("1151" to roster("CS1", "CS2")), hiding = setOf("1151:CS2"), backend = server)

        server.uploadRoster("1151", "CS1", "CS2")
        assertEquals(setOf("client:1151:CS1"), server.rows)
    }

    @Test
    fun `a hidden course in a term with nothing cached stays deleted, with no reset`() = runTest {
        val server = FakeCourseServer(rows = setOf("client:1151:CS2", "client:1142:CS9"))

        keepLocalCourses(listOf("1151" to emptyList()), hiding = setOf("1151:CS2"), backend = server)

        assertEquals(emptyList<String>(), server.resets)
        server.uploadRoster("1151", "CS2")
        assertEquals(setOf("client:1142:CS9"), server.rows)
    }

    @Test
    fun `a legacy entry, which hides its course in every term, keeps it deleted`() = runTest {
        val server = FakeCourseServer(
            rows = setOf("client:1151:CS1"),
            tombstones = mutableMapOf("client:1151:CS2" to false),
        )

        keepLocalCourses(listOf("1151" to roster("CS1")), hiding = setOf("CS2"), backend = server)

        server.uploadRoster("1151", "CS1", "CS2")
        assertEquals(setOf("client:1151:CS1"), server.rows)
    }

    @Test
    fun `hand-typed courses and every term with courses go back up`() = runTest {
        val server = FakeCourseServer(rows = setOf("client:1151:CS1", "client:1142:CS9"))
        val manual = Course(courseNo = "MY1", courseName = "Mine", isManual = true)

        keepLocalCourses(
            listOf("1151" to roster("CS1") + manual, "1142" to roster("CS9", "CS8")),
            hiding = emptySet(),
            backend = server,
        )

        assertEquals(
            setOf("client:1151:CS1", "client:1151:MY1", "client:1142:CS9", "client:1142:CS8"),
            server.rows,
        )
    }

    @Test
    fun `only the terms it uploads are reset, one at a time and never all at once`() = runTest {
        val server = FakeCourseServer(rows = setOf("client:1151:CS1", "client:1142:CS9"))

        keepLocalCourses(
            listOf("1151" to roster("CS1"), "1142" to emptyList()),
            hiding = emptySet(),
            backend = server,
        )

        assertEquals(listOf("1151"), server.resets)
        assertEquals(setOf("client:1151:CS1", "client:1142:CS9"), server.rows)
    }

    @Test
    fun `a failed request is thrown, and a retry finishes the job`() = runTest {
        val server = FakeCourseServer(rows = setOf("client:1151:CS1", "client:1151:CS2"))
        val terms = listOf("1151" to roster("CS1", "CS2"))
        server.failingDeletes = 1

        assertThrown<IOException> { keepLocalCourses(terms, hiding = setOf("1151:CS2"), backend = server) }
        keepLocalCourses(terms, hiding = setOf("1151:CS2"), backend = server)

        server.uploadRoster("1151", "CS1", "CS2")
        assertEquals(setOf("client:1151:CS1"), server.rows)
    }

    @Test
    fun `nothing more is sent once the sign-in it was answered under is gone`() = runTest {
        val server = FakeCourseServer(rows = setOf("client:1151:CS1", "client:1142:CS9"))
        var checks = 0

        assertThrown<CancellationException> {
            keepLocalCourses(
                listOf("1151" to roster("CS1"), "1142" to roster("CS9")),
                hiding = emptySet(),
                backend = server,
                // Signed out once the first request has gone.
                isCurrent = { ++checks == 1 },
            )
        }

        assertEquals(listOf("1151"), server.resets)
        assertEquals(setOf("client:1142:CS9"), server.rows)
    }
}

/**
 * What Use Local does around its requests ([useLocalCourses]): its terms are
 * latched and stamped as a class-table reset's are, so no sync reconciles a
 * term half-way through, and a failure asks the question again.
 */
class UseLocalCoursesTest {

    private val events = mutableListOf<String>()
    private val held = mutableSetOf<String>()
    private var current = true

    private val host = object : UseLocalHost {
        override fun beginReset(semester: String): Boolean = held.add(semester).also { if (it) events += "latch $semester" }
        override fun endReset(semester: String) {
            held.remove(semester)
            events += "release $semester"
        }
        override suspend fun stampReset(semester: String, atMs: Long) {
            events += "stamp $semester"
        }
        override fun askAgain() {
            events += "ask again"
        }
    }

    private fun roster(vararg courseNos: String) = courseNos.map { Course(courseNo = it, courseName = it) }

    private suspend fun run(server: CourseSyncBackend, vararg terms: Pair<String, List<Course>>) = useLocalCourses(
        terms = terms.toList(),
        hiding = emptySet(),
        backend = server,
        host = host,
        now = { 42L },
        isCurrent = { current },
    )

    private class Failing : CourseSyncBackend {
        override suspend fun deleteAllCourses(semester: String) = throw IOException("offline")
        override suspend fun uploadCourses(courses: List<Course>, semester: String) = Unit
        override suspend fun deleteCourse(courseKey: String) = Unit
    }

    private class Recording(private val onRequest: () -> Unit = {}) : CourseSyncBackend {
        override suspend fun deleteAllCourses(semester: String) = onRequest()
        override suspend fun uploadCourses(courses: List<Course>, semester: String) = onRequest()
        override suspend fun deleteCourse(courseKey: String) = onRequest()
    }

    @Test
    fun `its terms are latched for the requests, stamped, and only then released`() = runTest {
        run(Recording { events += "request" }, "1151" to roster("CS1"))

        assertEquals(listOf("latch 1151", "request", "request", "stamp 1151", "release 1151"), events)
    }

    @Test
    fun `a failed request asks the question again, and still releases its terms`() = runTest {
        run(Failing(), "1151" to roster("CS1"))

        assertEquals(listOf("latch 1151", "ask again", "stamp 1151", "release 1151"), events)
        assertEquals(emptySet<String>(), held)
    }

    @Test
    fun `a sign-out midway asks nothing of the next account and stamps nothing, but releases its terms`() = runTest {
        val thrown = runCatching {
            run(Recording { current = false }, "1151" to roster("CS1"), "1142" to roster("CS9"))
        }.exceptionOrNull()

        assertTrue("got $thrown", thrown is CancellationException)
        assertEquals(listOf("latch 1151", "latch 1142", "release 1151", "release 1142"), events)
    }

    @Test
    fun `a term a class-table reset holds is left for that reset to release`() = runTest {
        held += "1142"

        run(Recording(), "1151" to roster("CS1"), "1142" to roster("CS9"))

        assertEquals(setOf("1142"), held)
        assertTrue("release 1142" !in events)
    }
}

/**
 * One device's view of the backend: rows and tombstones by course key, every
 * tombstone written by this device. A tombstone's value is its
 * `deleted_by_reset`: a reset tombstone does not bind the device that wrote
 * it, so its next upload releases it; a single delete binds every device.
 */
private class FakeCourseServer(
    rows: Set<String>,
    val tombstones: MutableMap<String, Boolean> = mutableMapOf(),
) : CourseSyncBackend {
    val rows = rows.toMutableSet()

    /** The terms reset, in order. */
    val resets = mutableListOf<String>()

    /** How many of the next [deleteCourse] calls fail before reaching the server. */
    var failingDeletes = 0

    override suspend fun deleteAllCourses(semester: String) {
        resets += semester
        val prefix = "client:$semester:"
        val removed = rows.filter { it.startsWith(prefix) }
        rows -= removed.toSet()
        for (key in tombstones.keys.filter { it.startsWith(prefix) }) tombstones[key] = true
        for (key in removed) tombstones[key] = true
    }

    override suspend fun uploadCourses(courses: List<Course>, semester: String) {
        val keys = courses.map { "client:$semester:${it.courseNo}" }
        for (key in keys) if (tombstones[key] == true) tombstones.remove(key)
        rows += keys.filter { it !in tombstones }
    }

    override suspend fun deleteCourse(courseKey: String) {
        if (failingDeletes > 0) {
            failingDeletes--
            throw IOException("connection lost")
        }
        if (!rows.remove(courseKey)) return
        tombstones.putIfAbsent(courseKey, false)
    }
}
