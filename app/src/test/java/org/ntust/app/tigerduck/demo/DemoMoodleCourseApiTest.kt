package org.ntust.app.tigerduck.demo

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.network.moodle.MoodleCourseApi
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

    @Test
    fun `the demo student's submissions, comments and groups answer from the file, as Moodle would`() = runTest {
        val api = DemoMoodleCourseApi(moodle, readIds = { emptySet() }, markRead = {})
        val handedIn = moodle.courses.flatMap { c -> c.assignments.map { c to it } }
            .first { (_, a) -> a.submission?.isSubmitted == true && !a.submission.comments.isNullOrEmpty() }
        val (course, page) = handedIn

        assertTrue(api.functions().containsAll(listOf(MoodleCourseApi.COMMENTS_FUNCTION, MoodleCourseApi.GROUPS_FUNCTION)))
        val submission = api.submission(page.id)
        assertTrue(submission.isSubmitted)
        assertEquals("the comments and the group's name come from their own calls", null, submission.comments)
        assertEquals(null, submission.groupName)
        assertEquals(page.submission!!.comments, api.comments(page.cmid, submission.id!!))
        page.submission.groupId?.let { assertEquals(page.submission.groupName, api.groups(course.moodleId)[it]) }

        val notHandedIn = moodle.courses.flatMap { it.assignments }.first { it.submission?.isSubmitted != true }
        assertFalse(api.submission(notHandedIn.id).isSubmitted)
        // One with no record on Moodle, as an assignment the file does not describe.
        assertFalse(api.submission(1).isSubmitted)
        assertEquals(null, api.submission(1).id)
    }
}
