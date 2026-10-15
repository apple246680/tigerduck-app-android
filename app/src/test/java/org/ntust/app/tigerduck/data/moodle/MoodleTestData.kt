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
