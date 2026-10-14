package org.ntust.app.tigerduck.data.moodle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.network.MoodleWebserviceError
import org.ntust.app.tigerduck.network.moodle.MoodleCourseApi
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseGrades
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseSummary
import org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussion
import org.ntust.app.tigerduck.network.model.moodle.MoodleForum
import org.ntust.app.tigerduck.network.model.moodle.MoodleGradeItem
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberList
import java.io.IOException
import java.lang.reflect.Type

@OptIn(ExperimentalCoroutinesApi::class)
class MoodleCourseStoreTest {

    private class MemoryCache : MoodleCachePort {
        val files = mutableMapOf<String, Pair<Any, Long>>()

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> load(type: Type, name: String): Pair<T, Long>? = files[name] as Pair<T, Long>?

        override suspend fun save(value: Any, fetchedAtMs: Long, name: String) {
            files[name] = value to fetchedAtMs
        }
    }

    private class FakeApi : MoodleCourseApi {
        var allowed = setOf(
            MoodleCourseApi.FORUMS_FUNCTION, MoodleCourseApi.DISCUSSIONS_FUNCTION,
            MoodleCourseApi.VIEW_DISCUSSION_FUNCTION, MoodleCourseApi.MEMBERS_FUNCTION,
            MoodleCourseApi.GRADE_ITEMS_FUNCTION, MoodleCourseApi.COURSES_FUNCTION,
        )
        var forumList = listOf<MoodleForum>()
        var pages = mapOf<Int, List<MoodleDiscussion>>()
        var gradeCalls = 0
        var forumCalls = 0
        var discussionCalls = 0
        var memberCalls = 0
        var gate: CompletableDeferred<Unit>? = null
        var gradesAnswer: () -> List<MoodleGradeItem> = { emptyList() }
        var membersAnswer: () -> MoodleMemberList = { MoodleMemberList(null, emptyList()) }
        val viewed = mutableListOf<Int>()

        override suspend fun functions() = allowed
        override suspend fun forums(courseIds: List<Int>): List<MoodleForum> {
            forumCalls++
            return forumList.filter { it.courseId in courseIds }
        }
        var failNextPage = false
        override suspend fun discussions(forumId: Int, page: Int): List<MoodleDiscussion> {
            discussionCalls++
            if (failNextPage) {
                failNextPage = false
                throw IOException("offline")
            }
            return pages[page].orEmpty()
        }
        override suspend fun markViewed(discussionId: Int, isCurrent: () -> Boolean) {
            viewed += discussionId
        }
        override suspend fun members(courseId: Int): MoodleMemberList {
            memberCalls++
            return membersAnswer()
        }
        override suspend fun picture(url: String) = ByteArray(0)
        override suspend fun gradeItems(courseId: Int): List<MoodleGradeItem> {
            gradeCalls++
            gate?.await()
            return gradesAnswer()
        }
        override suspend fun summary(courseId: Int) = MoodleCourseSummary.EMPTY
        var downloads = 0
        var downloadAnswer: (java.io.File) -> Unit = { it.writeText("pdf bytes") }
        var lastLimit = 0L
        override suspend fun download(
            file: org.ntust.app.tigerduck.network.model.moodle.MoodleFile,
            target: java.io.File,
            maxBytes: Long,
        ) {
            downloads++
            lastLimit = maxBytes
            downloadAnswer(target)
        }
    }

    private var now = 1_000_000L
    private var signedIn = true
    private val cache = MemoryCache()
    private val api = FakeApi()

    @get:org.junit.Rule
    val tmp = org.junit.rules.TemporaryFolder()

    private fun TestScope.store() = MoodleCourseStore(
        courseId = 5,
        termCourseIds = listOf(6),
        courseCodes = listOf("CS3005301"),
        api = api,
        context = MoodleCacheContext("B1", cache, isCurrent = { signedIn }, now = { now }),
        scope = backgroundScope,
        openedFilesDir = tmp.root,
    )

    private val attachment = org.ntust.app.tigerduck.network.model.moodle.MoodleFile(
        name = "hw3.pdf", size = 9, mimeType = "application/pdf", modified = 1L, path = "/",
        url = "https://moodle2.ntust.edu.tw/webservice/pluginfile.php/1/mod_forum/attachment/90/hw3.pdf",
    )

    private val item = MoodleGradeItem(1, "HW1", "mod", "assign", 31, "95", 100.0, false, "")

    @Test
    fun `the cached copy shows before any fetch`() = runTest(UnconfinedTestDispatcher()) {
        cache.files["courses/5/grades"] = MoodleCourseGrades(listOf(item)) to 999L
        val store = store()
        store.awaitCache()

        assertEquals(listOf(item), store.grades.value!!.items)
        assertEquals(999L, store.grades.state.value.fetchedAtMs)
        assertEquals(0, api.gradeCalls)
    }

    @Test
    fun `a refresh asked for while one runs joins it`() = runTest(UnconfinedTestDispatcher()) {
        api.gate = CompletableDeferred()
        api.gradesAnswer = { listOf(item) }
        val store = store()

        val first = async { store.refreshGrades() }
        val second = async { store.refreshGrades() }
        runCurrent()
        api.gate!!.complete(Unit)
        first.await()
        second.await()

        assertEquals(1, api.gradeCalls)
        assertEquals(listOf(item), store.grades.value!!.items)
        assertEquals(now, cache.files["courses/5/grades"]!!.second)
    }

    @Test
    fun `a failed refresh keeps the copy and says why`() = runTest(UnconfinedTestDispatcher()) {
        cache.files["courses/5/grades"] = MoodleCourseGrades(listOf(item)) to 999L
        api.gradesAnswer = { throw IOException("offline") }
        val store = store()

        store.refreshGrades()

        assertEquals(listOf(item), store.grades.value!!.items)
        assertTrue(store.grades.state.value.failure is IOException)
        assertEquals("a failure is not dated as a fetch", 999L, store.grades.state.value.fetchedAtMs)
    }

    @Test
    fun `an answer that lands after a sign-out is neither shown nor kept`() = runTest(UnconfinedTestDispatcher()) {
        api.gradesAnswer = {
            signedIn = false
            listOf(item)
        }
        val store = store()

        store.refreshGrades()

        assertNull(store.grades.value)
        assertTrue(cache.files.isEmpty())
    }

    @Test
    fun `a site that does not allow a call hides its row rather than failing`() = runTest(UnconfinedTestDispatcher()) {
        api.allowed = emptySet()
        val store = store()

        store.refreshGrades()
        store.refreshMembers()
        store.refreshForums()

        assertTrue(store.grades.value!!.items.isEmpty())
        assertNull(store.grades.state.value.failure)
        assertTrue(store.sectionRows.value.isEmpty())
    }

    @Test
    fun `a course that hides its participants has no members row`() = runTest(UnconfinedTestDispatcher()) {
        api.membersAnswer = { throw MoodleWebserviceError.MalformedResponse("nopermissions") }
        val store = store()

        store.refreshMembers()

        assertTrue(store.members.value!!.members.isEmpty())
        assertNull(store.members.state.value.failure)
    }

    @Test
    fun `one forums call covers the term, and the course's news forum is its announcements`() = runTest(UnconfinedTestDispatcher()) {
        api.forumList = listOf(
            testForum(1, courseId = 5, type = "news", count = 2, unread = 1),
            testForum(2, courseId = 6, type = "news", count = 4),
        )
        val store = store()

        store.refreshForums()

        assertEquals(listOf(5, 6), store.forums.value!!.courseIds)
        assertEquals(1, store.newsForum.value!!.id)
        assertEquals(MoodleSectionRow(MoodleSectionRow.Kind.ANNOUNCEMENTS, 2, true), store.sectionRows.value.single())
    }

    @Test
    fun `opening an announcement clears its dot and logs the view`() = runTest(UnconfinedTestDispatcher()) {
        api.forumList = listOf(testForum(1, courseId = 5, type = "news", count = 2, unread = 1))
        api.pages = mapOf(0 to listOf(testDiscussion(10, unread = 1), testDiscussion(11)))
        val store = store()
        store.refreshForums()
        val pages = store.announcementPages()!!
        pages.refresh()
        assertEquals("loading the list marks nothing read", emptyList<Int>(), api.viewed)

        store.open(pages.list.value!!.discussions.first { it.id == 10 })
        runCurrent()

        assertEquals(listOf(10), api.viewed)
        assertEquals(0, pages.list.value!!.discussions.first { it.id == 10 }.unreadCount)
        assertEquals(false, store.sectionRows.value.single().hasUnread)
    }

    @Test
    fun `the next page loads at the list's end, and a failed one can be retried`() = runTest(UnconfinedTestDispatcher()) {
        api.forumList = listOf(testForum(1, courseId = 5, type = "news", count = 21))
        api.pages = mapOf(0 to (1..20).map { testDiscussion(it, created = 100L + it) })
        val store = store()
        store.refreshForums()
        val pages = store.announcementPages()!!
        pages.refresh()
        val last = MoodleDiscussions.sorted(pages.list.value!!).last().id
        assertTrue(pages.list.value!!.hasMore)

        api.failNextPage = true
        pages.loadMore(last)
        assertTrue(pages.pageFailure.value is IOException)
        assertEquals("a failed page leaves the list as it was", 20, pages.list.value!!.discussions.size)

        api.pages = api.pages + (1 to listOf(testDiscussion(21, created = 1)))
        pages.loadMore(last)

        assertNull(pages.pageFailure.value)
        assertEquals(21, pages.list.value!!.discussions.size)
        assertEquals(false, pages.list.value!!.hasMore)
    }

    @Test
    fun `an announcement opened with nothing cached fetches the forums and the list, once`() = runTest(UnconfinedTestDispatcher()) {
        api.forumList = listOf(testForum(1, courseId = 5, type = "news", count = 1))
        api.pages = mapOf(0 to listOf(testDiscussion(10)))
        val store = store()

        store.ensureAnnouncement(10)

        assertEquals(1, api.forumCalls)
        assertEquals(1, api.discussionCalls)
        assertEquals(listOf(10), store.announcementPages()!!.list.value!!.discussions.map { it.id })

        store.ensureAnnouncement(10)
        assertEquals("what is there is not fetched again", 1 to 1, api.forumCalls to api.discussionCalls)
    }

    @Test
    fun `a member opened with nothing cached fetches the members, once`() = runTest(UnconfinedTestDispatcher()) {
        api.membersAnswer = {
            MoodleMemberList(1, listOf(org.ntust.app.tigerduck.network.model.moodle.MoodleMember(
                7, "B1 @ Amy", null, null, null, emptyList(), emptyList(), null, "", emptyList(), emptyList(),
            )))
        }
        val store = store()

        store.ensureMember(7)
        store.ensureMember(7)

        assertEquals(1, api.memberCalls)
        assertEquals("B1 @ Amy", store.member(7)!!.fullName)
    }

    @Test
    fun `an attachment is downloaded into the app once, and opened from there after`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()

        val first = store.openAttachment(attachment)
        val second = store.openAttachment(attachment)

        val file = (first as MoodleAttachment.Local).file
        assertEquals("pdf bytes", file.readText())
        assertTrue("kept under the account's own folder", file.canonicalPath.startsWith(tmp.root.canonicalPath))
        assertEquals(file, (second as MoodleAttachment.Local).file)
        assertEquals(1, api.downloads)
    }

    @Test
    fun `a failed download leaves nothing behind and says why`() = runTest(UnconfinedTestDispatcher()) {
        api.downloadAnswer = { target ->
            target.writeText("half")
            throw IOException("connection reset")
        }
        val store = store()

        val result = store.openAttachment(attachment)

        assertTrue((result as MoodleAttachment.Failed).error is IOException)
        assertTrue(tmp.root.walkTopDown().none { it.isFile })
    }

    @Test
    fun `a file that is not on the Moodle site opens as a link, with no key and no download`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        val elsewhere = attachment.copy(url = "https://example.com/hw3.pdf")

        assertEquals(MoodleAttachment.Link("https://example.com/hw3.pdf"), store.openAttachment(elsewhere))
        assertEquals(0, api.downloads)
    }

    @Test
    fun `a member's picture is decoded off the thread that asked`() = runTest(UnconfinedTestDispatcher()) {
        val background = object : kotlinx.coroutines.CoroutineDispatcher() {
            var used = 0
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                used++
                block.run()
            }
        }
        var decoded = 0
        val store = MoodleCourseStore(
            courseId = 5, termCourseIds = emptyList(), courseCodes = emptyList(), api = api,
            context = MoodleCacheContext("B1", cache, isCurrent = { true }, now = { now }),
            scope = backgroundScope,
            decodePicture = { decoded++; null },
            background = background,
        )
        val member = org.ntust.app.tigerduck.network.model.moodle.MoodleMember(
            7, "B1 @ Amy", null, null, null, emptyList(), emptyList(),
            "https://moodle2.ntust.edu.tw/pluginfile.php/9/user/icon/f1?rev=3", "", emptyList(), emptyList(),
        )

        store.loadPicture(member)

        assertEquals(1, decoded)
        assertTrue(background.used > 0)
    }

    @Test
    fun `an announcement older than the first page is found on the pages after it`() = runTest(UnconfinedTestDispatcher()) {
        api.forumList = listOf(testForum(1, courseId = 5, type = "news", count = 25))
        api.pages = mapOf(
            0 to (1..20).map { testDiscussion(it, created = 1_000L - it) },
            1 to (21..25).map { testDiscussion(it, created = 1_000L - it) },
        )

        assertEquals(MoodleLookup.Found, store().ensureAnnouncement(24))
    }

    @Test
    fun `an announcement that is gone, or a fetch that failed, says so rather than waiting`() = runTest(UnconfinedTestDispatcher()) {
        api.forumList = listOf(testForum(1, courseId = 5, type = "news", count = 1))
        api.pages = mapOf(0 to listOf(testDiscussion(10)))
        assertEquals(MoodleLookup.Missing, store().ensureAnnouncement(99))

        cache.files.clear()
        api.failNextPage = true
        val failed = store().ensureAnnouncement(10)
        assertTrue((failed as MoodleLookup.Failed).error is IOException)
    }

    @Test
    fun `a member page says when its member is gone or the fetch failed`() = runTest(UnconfinedTestDispatcher()) {
        api.membersAnswer = { MoodleMemberList(1, emptyList()) }
        assertEquals(MoodleLookup.Missing, store().ensureMember(7))

        cache.files.clear()
        api.membersAnswer = { throw IOException("offline") }
        assertTrue((store().ensureMember(7) as MoodleLookup.Failed).error is IOException)
    }

    @Test
    fun `same-named files in different places, or a replaced file, get copies of their own`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        val first = (store.openAttachment(attachment) as MoodleAttachment.Local).file
        val elsewhere = (store.openAttachment(attachment.copy(url = attachment.url.replace("/90/", "/91/"))) as MoodleAttachment.Local).file
        val replaced = (store.openAttachment(attachment.copy(modified = 2L)) as MoodleAttachment.Local).file

        assertEquals(setOf("hw3.pdf"), setOf(first.name, elsewhere.name, replaced.name))
        assertEquals(3, setOf(first, elsewhere, replaced).size)
        assertEquals(3, api.downloads)
    }

    @Test
    fun `a file with no listed type is fetched again each time rather than trusted from before`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        val untyped = attachment.copy(mimeType = null)

        store.openAttachment(untyped)
        store.openAttachment(untyped)
        assertEquals(2, api.downloads)

        val unknown = attachment.copy(mimeType = "document/unknown")
        store.openAttachment(unknown)
        store.openAttachment(unknown)
        assertEquals("Moodle's unknown type checks nothing either", 4, api.downloads)
    }

    @Test
    fun `a download is held to the size Moodle lists, and an oversized file opens on the website`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        store.openAttachment(attachment)
        assertEquals(9L, api.lastLimit)

        val huge = attachment.copy(size = 2L * 1024 * 1024 * 1024)
        assertEquals(
            MoodleAttachment.Link("https://moodle2.ntust.edu.tw/pluginfile.php/1/mod_forum/attachment/90/hw3.pdf"),
            store.openAttachment(huge),
        )
        assertEquals(1, api.downloads)
    }

    @Test
    fun `a download that lands after a sign-out is deleted, not opened`() = runTest(UnconfinedTestDispatcher()) {
        api.downloadAnswer = { target ->
            target.writeText("pdf bytes")
            signedIn = false
        }
        val store = store()

        assertEquals(MoodleAttachment.Dropped, store.openAttachment(attachment))
        assertTrue(tmp.root.walkTopDown().none { it.isFile })
    }

    @Test
    fun `copies opened more than a day ago are pruned`() = runTest(UnconfinedTestDispatcher()) {
        val old = java.io.File(tmp.root, "stale-slot/old.pdf").apply {
            parentFile!!.mkdirs()
            writeText("old")
        }
        old.parentFile!!.setLastModified(System.currentTimeMillis() - 2 * 24 * 3_600_000L)

        store().openAttachment(attachment)

        assertTrue(!old.exists())
    }

    @Test
    fun `a file name cannot climb out of its folder`() = runTest(UnconfinedTestDispatcher()) {
        val sneaky = attachment.copy(name = "..\\..\\x/y.pdf")
        val file = (store().openAttachment(sneaky) as MoodleAttachment.Local).file

        assertTrue(file.canonicalPath.startsWith(tmp.root.canonicalPath))
        assertTrue(!file.name.contains('/') && !file.name.contains('\\') && !file.name.startsWith("."))
    }
}
