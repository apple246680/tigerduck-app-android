package org.ntust.app.tigerduck.data.cache

import android.app.Application
import java.io.File
import java.util.Date
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.CourseIgnores
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.data.model.PendingCourseIgnore
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The cache over real files, in Robolectric's per-test app directories. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class DataCacheTest {

    private lateinit var context: Application
    private lateinit var cache: DataCache

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val calendar = AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context))
        cache = DataCache(context, calendar)
    }

    private fun assignment(id: String, completed: Boolean = false) = Assignment(
        assignmentId = id,
        courseNo = "CS1001301",
        courseName = "Course",
        title = "Task $id",
        dueDate = Date(1_700_000_000_000),
        isCompleted = completed,
    )

    @Test
    fun `outstanding assignments leave out submitted, ignored and marked-done work`() = runTest {
        cache.saveAssignments(
            listOf(
                assignment("open"),
                assignment("submitted", completed = true),
                assignment("ignored"),
                assignment("marked"),
            )
        )
        cache.saveIgnoredAssignments(setOf("ignored"))
        cache.saveMarkedCompletedAssignments(setOf("marked"))

        assertEquals(listOf("open"), cache.loadOutstandingAssignments().map { it.assignmentId })
    }

    @Test
    fun `outstanding assignments leave out every assignment of an ignored course`() = runTest {
        cache.saveAssignments(
            listOf(
                assignment("open"),
                assignment("course-ignored").copy(courseNo = "IGNORED"),
            )
        )
        cache.saveIgnoredCourses(setOf(CourseIgnores.key(cache.currentSemesterCode(), "IGNORED")))

        assertEquals(listOf("open"), cache.loadOutstandingAssignments().map { it.assignmentId })
    }

    @Test
    fun `an assignment kept from an earlier term stays under that term's course ignore`() = runTest {
        cache.saveAssignments(listOf(assignment("kept").copy(courseNo = "OLD", semester = "1091")))
        cache.saveIgnoredCourses(setOf(CourseIgnores.key("1091", "OLD")))

        assertEquals(emptyList<String>(), cache.loadOutstandingAssignments().map { it.assignmentId })
    }

    @Test
    fun `signing out forgets the ignored courses and their unsent changes`() = runTest {
        cache.saveIgnoredCourses(setOf("1141:CS1001301"))
        cache.savePendingCourseIgnores(
            listOf(PendingCourseIgnore("1141", "CS1001301", "1141CS1001301", null, true, 1L))
        )

        cache.clearAllUserData()

        assertEquals(emptySet<String>(), cache.loadIgnoredCourses())
        assertEquals(emptyList<PendingCourseIgnore>(), cache.loadPendingCourseIgnores())
        assertFalse(File(context.filesDir, "TigerDuckData/ignored_courses.json").exists())
        assertFalse(File(context.filesDir, "TigerDuckData/pending_course_ignores.json").exists())
    }

    @Test
    fun `a waiting change too damaged to send is left out`() = runTest {
        cache.savePendingCourseIgnores(
            listOf(
                PendingCourseIgnore("1141", "CS1001301", "1141CS1001301", null, true, 1L),
                PendingCourseIgnore(null, "CS1001302", "1141CS1001302", null, true, 1L),
                PendingCourseIgnore("1141", "CS1001303", null, null, true, 1L),
            )
        )

        assertEquals(
            listOf("1141:CS1001301"),
            cache.loadPendingCourseIgnores().map { it.key },
        )
    }

    @Test
    fun `work begun before a sign-out writes nothing after it`() = runTest {
        val generation = cache.userDataGeneration
        cache.clearAllUserData()

        withContext(DataCache.Generation(generation)) {
            cache.saveAssignments(listOf(assignment("left behind")))
            cache.saveCourseCustomNames(mapOf("CS1001301" to mapOf("zh" to "Old name")))
        }

        assertEquals(emptyList<Assignment>(), cache.loadAssignments())
        assertEquals(emptyMap<String, Map<String, String>>(), cache.loadCourseCustomNames())
    }

    @Test
    fun `work tied to the account still signed in writes as usual`() = runTest {
        withContext(DataCache.Generation(cache.userDataGeneration)) {
            cache.saveAssignments(listOf(assignment("current")))
        }

        assertEquals(listOf("current"), cache.loadAssignments().map { it.assignmentId })
    }

    @Test
    fun `updating the assignments stores what the change makes of them`() = runTest {
        cache.saveAssignments(listOf(assignment("a"), assignment("b")))

        val written = cache.updateAssignments { all ->
            all.map { if (it.assignmentId == "a") it.copy(isCompleted = true) else it }
        }

        assertTrue(written)
        assertEquals(listOf(true, false), cache.loadAssignments().map { it.isCompleted })
    }

    @Test
    fun `a save made while the assignments update lands after it, not undone by it`() = runBlocking {
        cache.saveAssignments(listOf(assignment("read")))
        val changing = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val update = async(Dispatchers.IO) {
            cache.updateAssignments { all ->
                changing.countDown()
                finish.await()
                all.map { it.copy(isCompleted = true) }
            }
        }
        changing.await()

        // Another writer, such as the worker, saving while the update holds what it read.
        val save = async(Dispatchers.IO) { cache.saveAssignments(listOf(assignment("saved"))) }
        Thread.sleep(SAVE_CHANCE_MS)
        finish.countDown()
        update.await()
        save.await()

        assertEquals(listOf("saved"), cache.loadAssignments().map { it.assignmentId })
    }

    @Test
    fun `an update begun before a sign-out writes nothing after it`() = runTest {
        val generation = cache.userDataGeneration
        cache.clearAllUserData()
        cache.saveAssignments(listOf(assignment("next account")))

        val written = cache.updateAssignments(generation) { all -> all.map { it.copy(isCompleted = true) } }

        assertFalse(written)
        assertEquals(listOf(false), cache.loadAssignments().map { it.isCompleted })
    }

    @Test
    fun `writing the ignored or marked-done set tells readers to look again`() = runTest {
        val before = cache.assignmentMarksVersion.value

        cache.replaceIgnoredAssignments(setOf("a"))
        cache.replaceMarkedCompletedAssignments(setOf("b"))

        assertEquals(before + 2, cache.assignmentMarksVersion.value)
    }

    private companion object {
        // Time for a save that the update failed to hold off to land and be undone.
        const val SAVE_CHANCE_MS = 200L
    }
}
