package org.ntust.app.tigerduck.data.cache

import android.app.Application
import java.io.File
import java.util.Date
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.CourseIgnores
import org.ntust.app.tigerduck.data.model.Assignment
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
    fun `signing out forgets the ignored courses`() = runTest {
        cache.saveIgnoredCourses(setOf("1141:CS1001301"))

        cache.clearAllUserData()

        assertEquals(emptySet<String>(), cache.loadIgnoredCourses())
        assertFalse(File(context.filesDir, "TigerDuckData/ignored_courses.json").exists())
    }

    @Test
    fun `writing the ignored or marked-done set tells readers to look again`() = runTest {
        val before = cache.assignmentMarksVersion.value

        cache.replaceIgnoredAssignments(setOf("a"))
        cache.replaceMarkedCompletedAssignments(setOf("b"))

        assertEquals(before + 2, cache.assignmentMarksVersion.value)
    }
}
