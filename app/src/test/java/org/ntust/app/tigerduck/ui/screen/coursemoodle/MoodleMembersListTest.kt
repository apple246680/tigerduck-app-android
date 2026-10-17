package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Type

/**
 * A large class: the list draws the rows on screen, and fetches only their pictures. The search
 * is a row of the list above its top, as on iOS, where a pull on the list reveals it.
 */
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

    /** What Moodle answers the members fetch with; the large class unless a test sets another. */
    private var members: List<MoodleMember> = students

    private val api = object : MoodleCourseApi {
        override suspend fun functions() = setOf(MoodleCourseApi.MEMBERS_FUNCTION)
        override suspend fun forums(courseIds: List<Int>) = emptyList<org.ntust.app.tigerduck.network.model.moodle.MoodleForum>()
        override suspend fun discussions(forumId: Int, page: Int) = emptyList<org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussion>()
        override suspend fun markViewed(discussionId: Int, isCurrent: () -> Boolean) = Unit
        override suspend fun members(courseId: Int) = MoodleMemberList(0, members)
        override suspend fun picture(url: String): ByteArray {
            pictures++
            return ByteArray(0)
        }
        override suspend fun gradeItems(courseId: Int) = emptyList<MoodleGradeItem>()
        override suspend fun summary(courseId: Int) = MoodleCourseSummary.EMPTY
        override suspend fun download(file: MoodleFile, target: java.io.File, maxBytes: Long, progress: (Float) -> Unit) = Unit
    }

    private val cache = object : MoodleCachePort {
        override suspend fun <T> load(type: Type, name: String): Pair<T, Long>? = null
        override suspend fun save(value: Any, fetchedAtMs: Long, name: String) = Unit
    }

    private val search get() = RuntimeEnvironment.getApplication().getString(R.string.action_search)

    /** The list, with [members] fetched and on screen. */
    private fun showLoadedList() {
        val store = MoodleCourseStore(5, emptyList(), emptyList(), api, MoodleCacheContext("B1", cache, { true }), scope)
        composeRule.setContent {
            var query by remember { mutableStateOf("") }
            MembersList(store = store, query = query, onQueryChange = { query = it }, onOpen = {})
        }
        composeRule.waitUntil(5_000) { store.members.value?.members?.size == members.size }
        composeRule.waitForIdle()
    }

    @Test
    fun `only the rows on screen fetch their pictures`() {
        showLoadedList()

        assertTrue("fetched $pictures pictures", pictures in 1..40)
    }

    /** Off screen whether the list left the field uncomposed or composed and clipped above its top. */
    private fun assertSearchFieldOffScreen() {
        val fields = composeRule.onAllNodesWithText(search)
        if (fields.fetchSemanticsNodes().isNotEmpty()) fields.onFirst().assertIsNotDisplayed()
    }

    @Test
    fun `the search field waits above the list's top until the list is pulled down`() {
        showLoadedList()
        assertSearchFieldOffScreen()

        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)

        composeRule.onNodeWithText(search).assertIsDisplayed()
    }

    @Test
    fun `a class too short to fill the screen opens with the search field above the top too`() {
        members = students.take(2)
        showLoadedList()
        assertSearchFieldOffScreen()

        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)

        composeRule.onNodeWithText(search).assertIsDisplayed()
    }

    @Test
    fun `a class with nobody in it opens with the search field above the top too`() {
        members = emptyList()
        showLoadedList()
        assertSearchFieldOffScreen()

        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)

        composeRule.onNodeWithText(search).assertIsDisplayed()
    }

    @Test
    fun `the revealed field does not take the keyboard by itself`() {
        showLoadedList()

        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(0)

        composeRule.onNodeWithText(search).assertIsNotFocused()
    }
}
