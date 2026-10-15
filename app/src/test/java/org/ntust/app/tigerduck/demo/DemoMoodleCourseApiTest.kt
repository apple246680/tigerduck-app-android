package org.ntust.app.tigerduck.demo

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class DemoMoodleCourseApiTest {

    private val moodle = DemoFixture.parse(File("src/main/assets/demo.json").readText(), DemoFixture.LANG_EN).moodle!!
    private val course = moodle.courses.first { it.announcements.any { a -> a.unreadCount > 0 } }
    private val unreadId = course.announcements.first { it.unreadCount > 0 }.id

    @Test
    fun `an announcement read once stays read for the next start`() = runTest {
        val stored = mutableSetOf<Int>()
        DemoMoodleCourseApi(moodle, readIds = { stored }, markRead = { stored += it })
            .markViewed(unreadId) { true }

        // A new API over the same store, as after the app restarts.
        val next = DemoMoodleCourseApi(moodle, readIds = { stored }, markRead = { stored += it })
        val page = next.discussions(course.newsForumId, 0)

        assertEquals(0, page.first { it.id == unreadId }.unreadCount)
        val forum = next.forums(listOf(course.moodleId)).single()
        assertEquals(course.announcements.count { it.unreadCount > 0 } - 1, forum.unreadPostCount)
    }

    @Test
    fun `a view logged after a sign-out is not kept`() = runTest {
        val stored = mutableSetOf<Int>()
        DemoMoodleCourseApi(moodle, readIds = { stored }, markRead = { stored += it }).markViewed(unreadId) { false }
        assertEquals(emptySet<Int>(), stored)
    }
}
