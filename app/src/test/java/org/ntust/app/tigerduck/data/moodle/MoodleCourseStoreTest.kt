package org.ntust.app.tigerduck.data.moodle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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
        var gateForums: CompletableDeferred<Unit>? = null
        override suspend fun forums(courseIds: List<Int>): List<MoodleForum> {
            forumCalls++
            gateForums?.await()
            return forumList.filter { it.courseId in courseIds }
        }
        var failNextPage = false
        var discussionsGate: CompletableDeferred<Unit>? = null
        override suspend fun discussions(forumId: Int, page: Int): List<MoodleDiscussion> {
            discussionCalls++
            discussionsGate?.await()
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
        var downloadGate: CompletableDeferred<Unit>? = null
        /** One signal per download as it starts, its partial file written. */
        val downloadStarted = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        override suspend fun download(
            file: org.ntust.app.tigerduck.network.model.moodle.MoodleFile,
            target: java.io.File,
            maxBytes: Long,
        ) {
            downloads++
            lastLimit = maxBytes
            downloadAnswer(target)
            downloadStarted.trySend(Unit)
            downloadGate?.await()
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
        openedFilesDir = { tmp.root },
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

    @Test
    fun `opening a file twice at once downloads it once`() = runTest(UnconfinedTestDispatcher()) {
        api.downloadGate = CompletableDeferred()
        val store = store()

        val first = async { store.openAttachment(attachment) }
        api.downloadStarted.receive()
        val second = async { store.openAttachment(attachment) }
        runCurrent()
        api.downloadGate!!.complete(Unit)

        assertEquals(first.await(), second.await())
        assertTrue(first.await() is MoodleAttachment.Local)
        assertEquals(1, api.downloads)
    }

    @Test
    fun `a download stopped by a sign-out leaves no partial file`() = runTest(UnconfinedTestDispatcher()) {
        api.downloadAnswer = { it.writeText("half") }
        api.downloadGate = CompletableDeferred()
        val storeScope = kotlinx.coroutines.CoroutineScope(coroutineContext + kotlinx.coroutines.SupervisorJob(coroutineContext[kotlinx.coroutines.Job]))
        val store = MoodleCourseStore(
            courseId = 5, termCourseIds = emptyList(), courseCodes = emptyList(), api = api,
            context = MoodleCacheContext("B1", cache, isCurrent = { signedIn }, now = { now }),
            scope = storeScope, openedFilesDir = { tmp.root },
        )
        val opening = async { runCatching { store.openAttachment(attachment) } }
        api.downloadStarted.receive()
        assertTrue("the partial file is there while it downloads", tmp.root.walkTopDown().any { it.isFile })

        storeScope.cancel()
        opening.await()

        assertTrue(tmp.root.walkTopDown().none { it.isFile })
    }

    @Test
    fun `a file being fetched again is not pruned by another opened meanwhile`() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        val untyped = attachment.copy(mimeType = null)
        val first = (store.openAttachment(untyped) as MoodleAttachment.Local).file
        first.parentFile!!.setLastModified(System.currentTimeMillis() - 2 * 24 * 3_600_000L)

        api.downloadStarted.receive()
        api.downloadGate = CompletableDeferred()
        val again = async { store.openAttachment(untyped) }
        api.downloadStarted.receive()
        // Another file opened while the first is fetched again: its prune runs before its download.
        val other = async { store.openAttachment(attachment.copy(url = attachment.url.replace("/90/", "/92/"))) }
        api.downloadStarted.receive()
        api.downloadGate!!.complete(Unit)

        assertEquals(MoodleAttachment.Local(first), again.await())
        assertTrue(first.isFile)
        assertTrue(other.await() is MoodleAttachment.Local)
    }

    @Test
    fun `a long name is cut to fit the file system, keeping its extension`() = runTest(UnconfinedTestDispatcher()) {
        val long = attachment.copy(name = "資料結構期中考試題與詳解".repeat(12) + ".pdf")
        val file = (store().openAttachment(long) as MoodleAttachment.Local).file

        assertTrue(file.name.toByteArray().size <= 200)
        assertTrue(file.name.endsWith(".pdf"))
        assertTrue(file.name.startsWith("資料結構"))
    }

    @Test
    fun `a long name whose extension cannot be kept does not end in another`() = runTest(UnconfinedTestDispatcher()) {
        // Cut at 200 bytes, it would end in ".html", which another app reads as a web page.
        val long = attachment.copy(name = "a".repeat(195) + ".html." + "x".repeat(20), mimeType = "application/octet-stream")
        val file = (store().openAttachment(long) as MoodleAttachment.Local).file

        assertTrue(file.name.toByteArray().size <= 200)
        assertTrue("got ${file.name}", !file.name.contains('.'))
    }

    @Test
    fun `every store dates and prunes the opened files under one lock`() = runTest(UnconfinedTestDispatcher()) {
        val opening: kotlinx.coroutines.Deferred<MoodleAttachment>
        synchronized(MoodleCourseStore.OPENED_FILES_LOCK) {
            // Another store, sharing the folder, pruning: this one waits rather than date a slot
            // that prune is deciding on.
            opening = async(kotlinx.coroutines.Dispatchers.IO) { store().openAttachment(attachment) }
            Thread.sleep(300)
            assertTrue("no slot made while the lock is held", tmp.root.listFiles().isNullOrEmpty())
        }

        assertTrue(opening.await() is MoodleAttachment.Local)
    }

    @Test
    fun `each term keeps its own copy of the forums`() = runTest(UnconfinedTestDispatcher()) {
        api.forumList = listOf(
            testForum(1, courseId = 5, type = "news", count = 2),
            testForum(7, courseId = 3, type = "news", count = 9),
        )
        fun termStore(courseId: Int, term: String) = MoodleCourseStore(
            courseId = courseId, termCourseIds = emptyList(), courseCodes = emptyList(), api = api,
            context = MoodleCacheContext("B1", cache, isCurrent = { signedIn }, now = { now }),
            scope = backgroundScope, term = term,
        )
        termStore(5, "1151").refreshForums()
        // A course of an older term, opened after.
        termStore(3, "1142").refreshForums()

        // Offline, after a restart: the current course's announcements come from its own term's copy.
        val reopened = termStore(5, "1151")
        reopened.awaitCache()

        assertEquals(1, reopened.newsForum.value?.id)
    }

    @Test
    fun `a course's term is read off its idnumbers`() {
        val ids = mapOf("1151CS3005301" to 5, "1151CS3204301" to 6, "1142CS2001301" to 3)
        assertEquals("1151", MoodleCourseStore.term(5, ids))
        assertEquals("1142", MoodleCourseStore.term(3, ids))
        assertNull("none known", MoodleCourseStore.term(99, ids))
    }

    @Test
    fun `an announcement opened while the list refreshes stays read`() = runTest(UnconfinedTestDispatcher()) {
        api.forumList = listOf(testForum(1, courseId = 5, type = "news", count = 2, unread = 1))
        api.pages = mapOf(0 to listOf(testDiscussion(10, unread = 1), testDiscussion(11)))
        val store = store()
        store.refreshForums()
        val pages = store.announcementPages()!!
        pages.refresh()

        // The answer under way was read before Moodle logged the view, so it still says unread.
        api.discussionsGate = CompletableDeferred()
        val refresh = async { pages.refresh() }
        runCurrent()
        store.open(pages.list.value!!.discussions.first { it.id == 10 })
        api.discussionsGate!!.complete(Unit)
        refresh.await()

        assertEquals(0, pages.list.value!!.discussions.first { it.id == 10 }.unreadCount)
        assertEquals(false, store.sectionRows.value.single().hasUnread)
        @Suppress("UNCHECKED_CAST")
        val saved = cache.files["courses/5/announcements"]!!.first as org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussionList
        assertEquals("and so it is kept", 0, saved.discussions.first { it.id == 10 }.unreadCount)
    }

    @Test
    fun `a section row's dot cleared while the forums refresh stays cleared`() = runTest(UnconfinedTestDispatcher()) {
        api.forumList = listOf(testForum(1, courseId = 5, type = "news", count = 2, unread = 1))
        api.pages = mapOf(0 to listOf(testDiscussion(10, unread = 1), testDiscussion(11)))
        val store = store()
        store.refreshForums()
        val pages = store.announcementPages()!!
        pages.refresh()

        api.gateForums = CompletableDeferred()
        val refresh = async { store.refreshForums() }
        runCurrent()
        store.open(pages.list.value!!.discussions.first { it.id == 10 })
        api.gateForums!!.complete(Unit)
        refresh.await()

        assertEquals(false, store.sectionRows.value.single().hasUnread)
    }

    @Test
    fun `a forums answer that already counts the read keeps the other announcement's dot`() = runTest(UnconfinedTestDispatcher()) {
        api.forumList = listOf(testForum(1, courseId = 5, type = "news", count = 2, unread = 2))
        api.pages = mapOf(0 to listOf(testDiscussion(10, unread = 1), testDiscussion(11, unread = 1)))
        val store = store()
        store.refreshForums()
        val pages = store.announcementPages()!!
        pages.refresh()

        // Moodle logs the view before it answers the forums request under way, so the answer
        // already leaves the opened announcement out: replaying the read must not take it off again.
        api.gateForums = CompletableDeferred()
        val refresh = async { store.refreshForums() }
        runCurrent()
        store.open(pages.list.value!!.discussions.first { it.id == 10 })
        api.forumList = listOf(testForum(1, courseId = 5, type = "news", count = 2, unread = 1))
        api.gateForums!!.complete(Unit)
        refresh.await()

        assertEquals(1, store.forums.value!!.forums.single().unreadPostCount)
        assertEquals(true, store.sectionRows.value.single().hasUnread)
    }
}
