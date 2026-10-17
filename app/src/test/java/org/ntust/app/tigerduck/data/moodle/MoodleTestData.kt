package org.ntust.app.tigerduck.data.moodle

import org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussion
import org.ntust.app.tigerduck.network.model.moodle.MoodleForum

internal fun testForum(id: Int, courseId: Int, type: String, count: Int, unread: Int = 0) =
    MoodleForum(id, courseId, type, "Forum $id", cmid = id * 10, discussionCount = count, unreadPostCount = unread, intro = null)

internal fun testDiscussion(id: Int, created: Long = id.toLong(), pinned: Boolean = false, unread: Int = 0) =
    MoodleDiscussion(
        id = id, postId = id * 10, title = "D$id", message = "<p>M$id</p>", preview = "M$id",
        created = created, modified = created, isPinned = pinned, unreadCount = unread, replyCount = 0,
        authorName = null, authorId = null, attachments = emptyList(),
    )

internal fun testRecord(id: Int, courseId: Int = 5, due: Long = 0, noSubmissions: Boolean = false, team: Boolean = false) =
    org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentRecord(
        assignId = id, cmid = id * 10, courseId = courseId, name = "A$id", dueDate = due, cutoffDate = 0,
        allowSubmissionsFrom = 0, intro = "<p>Do A$id</p>", introAttachments = emptyList(),
        noSubmissions = noSubmissions, teamSubmission = team,
    )

/** A Moodle that answers nothing, for a test that fakes only the calls it is about. */
internal open class StubMoodleCourseApi : org.ntust.app.tigerduck.network.moodle.MoodleCourseApi {
    override suspend fun functions(): Set<String> = emptySet()
    override suspend fun forums(courseIds: List<Int>) = emptyList<MoodleForum>()
    override suspend fun discussions(forumId: Int, page: Int) = emptyList<MoodleDiscussion>()
    override suspend fun markViewed(discussionId: Int, isCurrent: () -> Boolean) = Unit
    override suspend fun members(courseId: Int) = org.ntust.app.tigerduck.network.model.moodle.MoodleMemberList(null, emptyList())
    override suspend fun picture(url: String) = ByteArray(0)
    override suspend fun gradeItems(courseId: Int) = emptyList<org.ntust.app.tigerduck.network.model.moodle.MoodleGradeItem>()
    override suspend fun summary(courseId: Int) = org.ntust.app.tigerduck.network.model.moodle.MoodleCourseSummary.EMPTY
    override suspend fun submission(assignId: Int): org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentSubmission =
        throw UnsupportedOperationException("not asked here")
    override suspend fun comments(cmid: Int, submissionId: Int) =
        emptyList<org.ntust.app.tigerduck.network.model.moodle.MoodleSubmissionComment>()
    override suspend fun groups(courseId: Int) = emptyMap<Int, String>()
    override suspend fun download(file: org.ntust.app.tigerduck.network.model.moodle.MoodleFile, target: java.io.File, maxBytes: Long) = Unit
}
