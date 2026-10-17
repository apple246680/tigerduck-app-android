package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.moodle.MoodleCacheContext
import org.ntust.app.tigerduck.data.moodle.MoodleCachePort
import org.ntust.app.tigerduck.data.moodle.MoodleCourseStore
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseSummary
import org.ntust.app.tigerduck.network.model.moodle.MoodleFile
import org.ntust.app.tigerduck.network.model.moodle.MoodleGradeItem
import org.ntust.app.tigerduck.network.model.moodle.MoodleMember
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberList
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberRole
import org.ntust.app.tigerduck.network.moodle.MoodleCourseApi
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Type

/** A large class: the list draws the rows on screen, and fetches only their pictures. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MoodleMembersListTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private var pictures = 0

    private val students = (1..120).map { i ->
        MoodleMember(
            id = i, fullName = "B1130%04d @ Student %d".format(i, i), idNumber = null, department = null, email = null,
            groups = emptyList(), roles = listOf(MoodleMemberRole("student", "", 0)),
            pictureUrl = "https://moodle2.ntust.edu.tw/pluginfile.php/$i/user/icon/f1?rev=1",
            description = "", interests = emptyList(), fields = emptyList(),
        )
    }

    private val api = object : MoodleCourseApi {
        override suspend fun functions() = setOf(MoodleCourseApi.MEMBERS_FUNCTION)
        override suspend fun forums(courseIds: List<Int>) = emptyList<org.ntust.app.tigerduck.network.model.moodle.MoodleForum>()
        override suspend fun discussions(forumId: Int, page: Int) = emptyList<org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussion>()
        override suspend fun markViewed(discussionId: Int, isCurrent: () -> Boolean) = Unit
        override suspend fun members(courseId: Int) = MoodleMemberList(0, students)
        override suspend fun picture(url: String): ByteArray {
            pictures++
            return ByteArray(0)
        }
        override suspend fun gradeItems(courseId: Int) = emptyList<MoodleGradeItem>()
        override suspend fun summary(courseId: Int) = MoodleCourseSummary.EMPTY
        override suspend fun submission(assignId: Int) = throw UnsupportedOperationException("not asked here")
        override suspend fun comments(cmid: Int, submissionId: Int) = emptyList<org.ntust.app.tigerduck.network.model.moodle.MoodleSubmissionComment>()
        override suspend fun groups(courseId: Int) = emptyMap<Int, String>()
        override suspend fun download(file: MoodleFile, target: java.io.File, maxBytes: Long) = Unit
    }

    private val cache = object : MoodleCachePort {
        override suspend fun <T> load(type: Type, name: String): Pair<T, Long>? = null
        override suspend fun save(value: Any, fetchedAtMs: Long, name: String) = Unit
    }

    @Test
    fun `only the rows on screen fetch their pictures`() {
        val store = MoodleCourseStore(5, emptyList(), emptyList(), api, MoodleCacheContext("B1", cache, { true }), scope)
        composeRule.setContent {
            MembersList(store = store, searching = false, query = "", onQueryChange = {}, onOpen = {})
        }
        composeRule.waitUntil(5_000) { store.members.value?.members?.size == 120 }
        composeRule.waitForIdle()

        assertTrue("fetched $pictures pictures", pictures in 1..40)
    }
}
