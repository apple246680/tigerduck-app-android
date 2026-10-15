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
import org.ntust.app.tigerduck.push.ServerCourse
import org.ntust.app.tigerduck.shared.Course
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * A course added by hand whose upload failed ([PendingCourseAdds]), over real files
 * (tigerduck-app-android#347). It used to go up once: in a term the server already had, a
 * failed upload left the course on this phone alone, never to reach another device, and lost
 * at sign-out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PendingCourseAddsTest {

    private lateinit var cache: DataCache
    private lateinit var adds: PendingCourseAdds

    private data class Upload(val courseNos: List<String>, val semester: String, val forceKeys: List<String>)

    private val uploads = mutableListOf<Upload>()
    private var answer: suspend () -> List<String>? = { emptyList() }

    private val term = "1151"
    private val portal = Course(courseNo = "CS1010001", courseName = "Portal")
    private val typed = Course(courseNo = "MA1010002", courseName = "Typed", isManual = true)

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context)))
        adds = PendingCourseAdds(cache) { courses, semester, forceKeys ->
            uploads += Upload(courses.map { it.courseNo }, semester, forceKeys)
            answer()
        }
    }

    private fun row(courseNo: String, semester: String = term) =
        ServerCourse(courseNo = courseNo, courseName = courseNo, semester = semester)

    @Test
    fun `an add whose upload fails is kept, and the next sync sends it again with its force key`() = runTest {
        cache.saveCourses(listOf(portal, typed), term)
        answer = { throw IOException("offline") }
        adds.send(listOf(portal, typed), term, typed.courseNo)
        assertEquals(mapOf(term to listOf("MA1010002")), cache.loadPendingCourseAdds())

        answer = { listOf("MA1010002") }
        adds.retry()

        assertEquals(Upload(listOf("MA1010002"), term, listOf("client:1151:MA1010002")), uploads.last())
        assertEquals(emptyMap<String, List<String>>(), cache.loadPendingCourseAdds())
    }

    @Test
    fun `an add that lands is not kept, and one refused by the server waits for the next sync`() = runTest {
        cache.saveCourses(listOf(typed), term)
        adds.send(listOf(typed), term, typed.courseNo)
        assertEquals(emptyMap<String, List<String>>(), cache.loadPendingCourseAdds())

        answer = { throw PushApiException("uploadCourses failed: HTTP 500") }
        adds.send(listOf(typed), term, typed.courseNo)
        adds.retry()
        assertEquals(mapOf(term to listOf("MA1010002")), cache.loadPendingCourseAdds())
    }

    @Test
    fun `an add the server lists in a snapshot is let go`() = runTest {
        cache.saveCourses(listOf(typed), term)
        answer = { throw IOException("timed out after the server took it") }
        adds.send(listOf(typed), term, typed.courseNo)

        adds.listed(listOf(row("MA1010002", semester = "1142")))
        assertEquals("only the same term's row counts", mapOf(term to listOf("MA1010002")), cache.loadPendingCourseAdds())

        adds.listed(listOf(row("MA1010002")))
        assertEquals(emptyMap<String, List<String>>(), cache.loadPendingCourseAdds())
    }

    @Test
    fun `an add deleted or reset away since is not sent again`() = runTest {
        cache.saveCourses(listOf(typed), term)
        answer = { throw IOException("offline") }
        adds.send(listOf(typed), term, typed.courseNo)
        cache.saveCourses(emptyList(), term)
        uploads.clear()

        answer = { emptyList() }
        adds.retry()

        assertEquals(emptyList<Upload>(), uploads)
        assertEquals(emptyMap<String, List<String>>(), cache.loadPendingCourseAdds())
    }

    @Test
    fun `nothing is kept for the next account`() = runTest {
        cache.saveCourses(listOf(typed), term)
        answer = { throw IOException("offline") }
        adds.send(listOf(typed), term, typed.courseNo)

        cache.clearAllUserData()

        assertEquals(emptyMap<String, List<String>>(), cache.loadPendingCourseAdds())
    }
}
