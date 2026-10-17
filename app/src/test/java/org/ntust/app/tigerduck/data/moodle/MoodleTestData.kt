package org.ntust.app.tigerduck.data.moodle

import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseMaterials
import org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussion
import org.ntust.app.tigerduck.network.model.moodle.MoodleFile
import org.ntust.app.tigerduck.network.model.moodle.MoodleForum
import org.ntust.app.tigerduck.network.model.moodle.MoodleMaterial
import org.ntust.app.tigerduck.network.model.moodle.MoodleMaterialSection
import org.ntust.app.tigerduck.network.model.moodle.MoodlePost

internal fun testForum(id: Int, courseId: Int, type: String, count: Int, unread: Int = 0) =
    MoodleForum(id, courseId, type, "Forum $id", cmid = id * 10, discussionCount = count, unreadPostCount = unread, intro = null)

internal fun testDiscussion(id: Int, created: Long = id.toLong(), pinned: Boolean = false, unread: Int = 0) =
    MoodleDiscussion(
        id = id, postId = id * 10, title = "D$id", message = "<p>M$id</p>", preview = "M$id",
        created = created, modified = created, isPinned = pinned, unreadCount = unread, replyCount = 0,
        authorName = null, authorId = null, attachments = emptyList(),
    )

internal fun testPost(id: Int, parent: Int? = null, author: String? = "B$id @ Author $id", unread: Boolean = false) =
    MoodlePost(
        id = id, parentId = parent, subject = "S$id", message = "<p>P$id</p>", created = id.toLong(),
        authorName = author, authorId = id, attachments = emptyList(), isUnread = unread, isDeleted = false,
    )

internal fun testFile(name: String, modified: Long? = 1L, path: String = "/", mime: String? = "application/pdf", size: Long = 9) =
    MoodleFile(
        name = name, size = size, mimeType = mime, modified = modified, path = path,
        url = "https://moodle2.ntust.edu.tw/webservice/pluginfile.php/1/mod_resource/content/1$path$name",
    )

internal fun testMaterials() = MoodleCourseMaterials(
    listOf(
        MoodleMaterialSection(
            1, "",
            listOf(
                MoodleMaterial(101, 7, MoodleMaterial.RESOURCE, "Syllabus", listOf(testFile("syllabus.pdf")), null),
                MoodleMaterial(102, 8, MoodleMaterial.URL, "Slides", emptyList(), "https://www.example.com/ds"),
            ),
        ),
        MoodleMaterialSection(
            2, "Week 1",
            listOf(
                MoodleMaterial(
                    103, 9, MoodleMaterial.FOLDER, "Labs",
                    listOf(testFile("lab1.zip", path = "/a/"), testFile("lab2.zip", path = "/b/")), null,
                ),
            ),
        ),
    ),
)
