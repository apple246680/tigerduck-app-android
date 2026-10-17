package org.ntust.app.tigerduck.data.cache

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentRecord
import org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentSubmission
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseGrades
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseSummary
import org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussion
import org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussionList
import org.ntust.app.tigerduck.network.model.moodle.MoodleFile
import org.ntust.app.tigerduck.network.model.moodle.MoodleForum
import org.ntust.app.tigerduck.network.model.moodle.MoodleGradeItem
import org.ntust.app.tigerduck.network.model.moodle.MoodleMember
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberField
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberList
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberRole
import org.ntust.app.tigerduck.network.model.moodle.MoodleSubmissionComment
import org.ntust.app.tigerduck.network.model.moodle.MoodleTermForums
import java.lang.reflect.Modifier

/**
 * The Moodle cache drops every copy in another shape by its schema number, so the number must
 * move with the shape. Changed a field in network/model/moodle? Bump
 * DataCache.MOODLE_CACHE_SCHEMA, then paste the new shape below with the new number.
 */
class MoodleCacheSchemaTest {

    private val persisted = listOf(
        MoodleAssignmentRecord::class, MoodleAssignmentSubmission::class, MoodleCourseGrades::class, MoodleCourseSummary::class, MoodleDiscussion::class,
        MoodleDiscussionList::class, MoodleFile::class, MoodleForum::class, MoodleGradeItem::class,
        MoodleMember::class, MoodleMemberField::class, MoodleMemberList::class, MoodleMemberRole::class,
        MoodleSubmissionComment::class, MoodleTermForums::class,
    )

    private fun shape(): String = persisted.joinToString("\n") { kind ->
        val fields = kind.java.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && !Modifier.isTransient(it.modifiers) }
            .sortedBy { it.name }
            .joinToString(", ") { "${it.name}: ${it.genericType.typeName.replace(PACKAGE, "")}" }
        "${kind.simpleName}($fields)"
    }

    @Test
    fun `the cache schema moves with the shape of what it keeps`() {
        assertEquals(1, DataCache.MOODLE_CACHE_SCHEMA)
        assertEquals(SHAPE_AT_SCHEMA_1, shape())
    }

    private companion object {
        val PACKAGE = Regex("""\b(?:[a-z]\w*\.)+""")

        val SHAPE_AT_SCHEMA_1 = """
            MoodleAssignmentRecord(allowSubmissionsFrom: long, assignId: int, cmid: int, courseId: int, cutoffDate: long, dueDate: long, intro: String, introAttachments: List<MoodleFile>, name: String, noSubmissions: boolean, teamSubmission: boolean)
            MoodleAssignmentSubmission(comments: List<MoodleSubmissionComment>, files: List<MoodleFile>, groupId: Integer, groupName: String, id: Integer, modified: Long, onlineText: String, status: String, takesComments: boolean)
            MoodleCourseGrades(items: List<MoodleGradeItem>)
            MoodleCourseSummary(html: String, names: List<String>, text: String)
            MoodleDiscussion(attachments: List<MoodleFile>, authorId: Integer, authorName: String, created: long, id: int, isPinned: boolean, message: String, modified: long, postId: int, preview: String, replyCount: int, title: String, unreadCount: int)
            MoodleDiscussionList(discussions: List<MoodleDiscussion>, forumId: int, hasMore: boolean, pageCount: int)
            MoodleFile(mimeType: String, modified: Long, name: String, path: String, size: long, url: String)
            MoodleForum(cmid: int, courseId: int, discussionCount: int, id: int, intro: String, name: String, type: String, unreadPostCount: int)
            MoodleGradeItem(cmid: Integer, feedback: String, grade: String, id: int, isHidden: boolean, maxGrade: Double, module: String, name: String, type: String)
            MoodleMember(department: String, description: String, email: String, fields: List<MoodleMemberField>, fullName: String, groups: List<String>, id: int, idNumber: String, interests: List<String>, pictureUrl: String, roles: List<MoodleMemberRole>)
            MoodleMemberField(name: String, value: String)
            MoodleMemberList(members: List<MoodleMember>, ownId: Integer)
            MoodleMemberRole(name: String, shortName: String, sortOrder: int)
            MoodleSubmissionComment(authorName: String, content: String, created: long, id: int)
            MoodleTermForums(courseIds: List<Integer>, forums: List<MoodleForum>)
        """.trimIndent()
    }
}
