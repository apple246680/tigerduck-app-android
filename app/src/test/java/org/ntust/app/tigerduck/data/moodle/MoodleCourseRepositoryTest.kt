package org.ntust.app.tigerduck.data.moodle

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseSummary
import org.ntust.app.tigerduck.network.model.moodle.MoodleFile
import org.ntust.app.tigerduck.network.model.moodle.MoodleGradeItem
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberList
import org.ntust.app.tigerduck.network.moodle.MoodleCourseApi
import java.lang.reflect.Type

/** One store per account and course, dropped at sign-out, on the demo account's Moodle in demo mode. */
@OptIn(ExperimentalCoroutinesApi::class)
class MoodleCourseRepositoryTest {

    private class Api(val grade: String) : MoodleCourseApi {
        override suspend fun functions() = setOf(MoodleCourseApi.GRADE_ITEMS_FUNCTION)
        override suspend fun forums(courseIds: List<Int>) = emptyList<org.ntust.app.tigerduck.network.model.moodle.MoodleForum>()
        override suspend fun discussions(forumId: Int, page: Int) = emptyList<org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussion>()
        override suspend fun markViewed(discussionId: Int, isCurrent: () -> Boolean) = Unit
        override suspend fun members(courseId: Int) = MoodleMemberList(null, emptyList())
        override suspend fun picture(url: String) = ByteArray(0)
        override suspend fun gradeItems(courseId: Int) =
            listOf(MoodleGradeItem(1, "HW", "mod", "assign", 2, grade, 100.0, false, ""))
        override suspend fun summary(courseId: Int) = MoodleCourseSummary.EMPTY
        override suspend fun download(file: MoodleFile, target: java.io.File) = Unit
    }

    private val authState = MutableStateFlow(true)
    private var account: String? = "B11300001"
    private var demo = false
    private val live = Api("live")
    private val demoApi = Api("demo")

    private val noCache = object : MoodleCachePort {
        override suspend fun <T> load(type: Type, name: String): Pair<T, Long>? = null
        override suspend fun save(value: Any, fetchedAtMs: Long, name: String) = Unit
    }

    private fun TestScope.repository() = MoodleCourseRepository(
        signedIn = { authState.value },
        account = { account },
        isDemo = { demo },
        liveApi = live,
        demoApi = { demoApi },
        idMap = { mapOf("1151CS3005301" to 5) },
        cachePort = { noCache },
        picturesDir = { null },
        openedFilesDir = { null },
        decode = { null },
        authState = authState,
        appScope = backgroundScope,
        newScope = { kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + kotlinx.coroutines.SupervisorJob()) },
    )

    @Test
    fun `one store per account and course`() = runTest(UnconfinedTestDispatcher()) {
        val repository = repository()

        val first = repository.store(5)
        assertSame(first, repository.store(5))
        assertNotSame(first, repository.store(6))

        account = "B11300002"
        val other = repository.store(5)
        assertNotSame("another account never gets the first one's store", first, other)
        assertFalse("and the first one's store knows it is no longer current", first!!.isCurrent())
        assertTrue(other!!.isCurrent())
    }

    @Test
    fun `sign-out drops every store, and none is handed out signed out`() = runTest(UnconfinedTestDispatcher()) {
        val repository = repository()
        val before = repository.store(5)

        authState.value = false
        assertNull(repository.store(5))
        assertFalse(before!!.isCurrent())

        authState.value = true
        assertNotSame("signing back in builds anew", before, repository.store(5))
    }

    @Test
    fun `the demo account's stores answer from the demo Moodle`() = runTest(UnconfinedTestDispatcher()) {
        demo = true
        val store = repository().store(5)!!

        store.refreshGrades()

        assertEquals("demo", store.grades.value!!.items.single().grade)
    }

    @Test
    fun `a real account's stores answer from Moodle`() = runTest(UnconfinedTestDispatcher()) {
        val store = repository().store(5)!!

        store.refreshGrades()

        assertEquals("live", store.grades.value!!.items.single().grade)
    }
}
