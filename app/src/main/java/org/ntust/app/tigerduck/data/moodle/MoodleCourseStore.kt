package org.ntust.app.tigerduck.data.moodle

import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.network.moodle.MoodleCourseApi
import org.ntust.app.tigerduck.network.moodle.MoodleFileUrls
import org.ntust.app.tigerduck.network.moodle.checkableMimeType
import org.ntust.app.tigerduck.network.model.moodle.MoodleFile
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseGrades
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseSummary
import org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussion
import org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussionList
import org.ntust.app.tigerduck.network.model.moodle.MoodleForum
import org.ntust.app.tigerduck.network.model.moodle.MoodleMember
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberList
import org.ntust.app.tigerduck.network.model.moodle.MoodleTermForums
import org.ntust.app.tigerduck.util.logSummary
import java.io.File

/**
 * A course's data on Moodle, for the course detail's Moodle section and the screens it opens.
 * Each kind is a [MoodleFeed], cached on the device for the signed-in account. One store per
 * account and course, shared by every screen of that course; see [MoodleCourseRepository].
 * Ported from iOS `MoodleCourseStore`.
 *
 * Phase 2 adds the course's materials, a forum's threads and file downloads beside these.
 */
class MoodleCourseStore(
    val courseId: Int,
    termCourseIds: List<Int>,
    private val courseCodes: List<String>,
    private val api: MoodleCourseApi,
    private val context: MoodleCacheContext,
    private val scope: CoroutineScope,
    /** Where member pictures are kept; null keeps them in memory only. */
    private val picturesDir: File? = null,
    /**
     * Where opened attachments are kept for the account, which another app reads them from. Asked
     * for off the main thread: finding it reads the install's salt from disk.
     */
    private val openedFilesDir: (() -> File?)? = null,
    private val decodePicture: (ByteArray) -> ImageBitmap? = { null },
    /** Where pictures are decoded, off the main thread. */
    private val background: CoroutineDispatcher = Dispatchers.Default,
) {
    /** Every course of the term, which one forums call covers. */
    val termCourseIds: List<Int> = (termCourseIds + courseId).distinct().sorted()

    val account: String get() = context.account

    fun isCurrent(): Boolean = context.isCurrent()

    val forums = feed<MoodleTermForums>("forums", MoodleRefreshInterval.FORUMS_MS)
    val grades = feed<MoodleCourseGrades>("courses/$courseId/grades", MoodleRefreshInterval.GRADES_MS)
    val members = feed<MoodleMemberList>("courses/$courseId/members", MoodleRefreshInterval.MEMBERS_MS)
    val summary = feed<MoodleCourseSummary>("courses/$courseId/summary", MoodleRefreshInterval.SUMMARY_MS)

    private inline fun <reified T : Any> feed(name: String, ttlMs: Long) =
        MoodleFeed<T>(name, object : TypeToken<T>() {}.type, ttlMs, context, scope)

    /** Returns once every cached copy is read, so a screen's first refresh knows their age. */
    suspend fun awaitCache() {
        forums.awaitCache()
        grades.awaitCache()
        members.awaitCache()
        summary.awaitCache()
    }

    // --- Section ---

    /** The section's rows, in order; a kind with nothing to count has none. */
    val sectionRows: StateFlow<List<MoodleSectionRow>> =
        combine(forums.state, grades.state, members.state) { f, g, m ->
            MoodleSections.rows(f.value, courseId, g.value, m.value)
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** What the foot of the section shows; null while the course has no summary worth showing. */
    val shownSummary: StateFlow<MoodleCourseSummary?> = summary.state
        .map { state -> state.value?.takeIf { MoodleSections.isWorthShowing(it, courseCodes) } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    // --- Forums ---

    /** When this course's forums were fetched; null while the copy does not cover the course. */
    val forumsFetchedAtMs: Long?
        get() = forums.state.value.takeIf { it.value?.courseIds?.contains(courseId) == true }?.fetchedAtMs

    val newsForum: StateFlow<MoodleForum?> = forums.state
        .map { MoodleSections.newsForum(it.value, courseId) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private fun currentNewsForum(): MoodleForum? = MoodleSections.newsForum(forums.value, courseId)

    /**
     * One call for every course of the term. A site that does not allow the forum calls answers
     * as if no course had a forum, which hides the announcements; one that does not allow the
     * posts call keeps only the announcements, as a discussion would show no reply.
     */
    suspend fun refreshForums() {
        val ids = termCourseIds
        forums.refresh {
            val functions = api.functions()
            if (!functions.containsAll(FORUM_FUNCTIONS)) return@refresh MoodleTermForums(ids, emptyList())
            val all = api.forums(ids)
            val shown = if (MoodleCourseApi.DISCUSSION_POSTS_FUNCTION in functions) all else all.filter { it.isNews }
            MoodleTermForums(ids, shown)
        }
    }

    // --- Discussions ---

    private val discussionPages = mutableMapOf<Int, MoodleDiscussionPages>()

    /** Keep announcement cache paths stable so upgrading does not discard their offline copies. */
    @Synchronized
    fun discussions(forum: MoodleForum): MoodleDiscussionPages = discussionPages.getOrPut(forum.id) {
        val feed = if (forum.isNews) {
            MoodleFeed<MoodleDiscussionList>(
                "courses/$courseId/announcements",
                object : TypeToken<MoodleDiscussionList>() {}.type,
                MoodleRefreshInterval.ANNOUNCEMENTS_MS,
                context,
                scope,
            )
        } else {
            MoodleFeed(
                "courses/$courseId/forums/${forum.id}",
                object : TypeToken<MoodleDiscussionList>() {}.type,
                MoodleRefreshInterval.FORUMS_MS,
                context,
                scope,
            )
        }
        MoodleDiscussionPages(forum.id, feed, byModified = !forum.isNews, forums, api, scope)
    }

    /** The announcements, once the course's news forum is known. */
    fun announcementPages(): MoodleDiscussionPages? = currentNewsForum()?.let(::discussions)

    /**
     * What an announcement opened with nothing cached needs, as after the system ended the app:
     * the course's forums, then its announcements, each fetched only when the cache lacks it, and
     * further pages while the announcement is older than those listed.
     */
    suspend fun ensureAnnouncement(discussionId: Int): MoodleLookup {
        awaitCache()
        if (currentNewsForum() == null) refreshForums()
        val pages = announcementPages()
            ?: return forums.state.value.failure?.let(MoodleLookup::Failed) ?: MoodleLookup.Missing
        pages.feed.awaitCache()
        fun listed(): Boolean = pages.feed.value?.takeIf { it.forumId == pages.forumId }
            ?.discussions.orEmpty().any { it.id == discussionId }
        if (listed()) return MoodleLookup.Found
        pages.refresh()
        if (!listed()) pages.feed.state.value.failure?.let { return MoodleLookup.Failed(it) }
        var loaded = 0
        while (!listed() && loaded++ < MAX_LOOKUP_PAGES) {
            val list = pages.feed.value?.takeIf { it.forumId == pages.forumId && it.hasMore } ?: break
            pages.loadMore(MoodleDiscussions.sorted(list, pages.byModified).lastOrNull()?.id)
            pages.pageFailure.value?.let { return MoodleLookup.Failed(it) }
        }
        return if (listed()) MoodleLookup.Found else MoodleLookup.Missing
    }

    /**
     * An announcement opened on screen: its unread mark goes here and on the section row at once,
     * and Moodle logs the view, which marks it read there. A list load never marks anything read.
     * The view event is best effort and not retried.
     */
    fun open(discussion: MoodleDiscussion, forum: MoodleForum? = currentNewsForum()) {
        if (!context.isCurrent()) return
        forum?.let { discussions(it).markRead(discussion.id) }
        scope.launch {
            try {
                if (!context.isCurrent()) return@launch
                if (MoodleCourseApi.VIEW_DISCUSSION_FUNCTION !in api.functions()) return@launch
                if (!context.isCurrent()) return@launch
                api.markViewed(discussion.id) { context.isCurrent() }
            } catch (e: CancellationException) {
                return@launch
            } catch (e: Exception) {
                Log.w(TAG, "discussion view not logged: ${e.logSummary()}")
            }
        }
    }

    // --- Grades ---

    /** A site that does not allow the call answers as if the course had none, which hides the row. */
    suspend fun refreshGrades() {
        grades.refresh {
            if (MoodleCourseApi.GRADE_ITEMS_FUNCTION !in api.functions()) return@refresh MoodleCourseGrades(emptyList())
            MoodleCourseGrades(api.gradeItems(courseId))
        }
    }

    // --- Members ---

    /** The member a post's author is, while the cached members have them. */
    fun member(id: Int?): MoodleMember? {
        id ?: return null
        return members.value?.members?.firstOrNull { it.id == id }
    }

    /** What a member's page opened with nothing cached needs: the members, when the cache lacks them. */
    suspend fun ensureMember(memberId: Int): MoodleLookup {
        awaitCache()
        if (member(memberId) != null) return MoodleLookup.Found
        refreshMembers()
        if (member(memberId) != null) return MoodleLookup.Found
        return members.state.value.failure?.let(MoodleLookup::Failed) ?: MoodleLookup.Missing
    }

    /**
     * The whole list in one call. A site that does not allow the call, or a course that keeps its
     * participants from the account, answers as if the course had no members, which hides their
     * row for good rather than failing on every refresh.
     */
    suspend fun refreshMembers() {
        members.refresh {
            if (MoodleCourseApi.MEMBERS_FUNCTION !in api.functions()) return@refresh MoodleMemberList(null, emptyList())
            try {
                api.members(courseId)
            } catch (e: org.ntust.app.tigerduck.network.MoodleWebserviceError.MalformedResponse) {
                if (e.detail == MoodleCourseApi.MEMBERS_HIDDEN_CODE) MoodleMemberList(null, emptyList()) else throw e
            }
        }
    }

    // --- Summary ---

    /** A site that does not allow the call answers as if the course had no summary. */
    suspend fun refreshSummary() {
        summary.refresh {
            if (MoodleCourseApi.COURSES_FUNCTION !in api.functions()) return@refresh MoodleCourseSummary.EMPTY
            api.summary(courseId)
        }
    }

    // --- Files ---

    private val _downloading = MutableStateFlow<Set<String>>(emptySet())

    /** The attachments being downloaded, by URL, for the rows to show progress on. */
    val downloading: StateFlow<Set<String>> = _downloading.asStateFlow()

    /**
     * An attachment ready to open: the copy in the app, downloaded first when there is none, or
     * for a file off the Moodle site, or one too large to download here, a link with no key in
     * it. Each file has a folder of its own, named for its link, place and version, so two files
     * of one name never share a copy and a replaced one gets a new copy; a file with no listed
     * type, which a download cannot check, is fetched again each time. A download stops past the
     * size Moodle lists, and copies opened more than a day ago go. Phase 2 adds progress,
     * cancelling and dropping copies Moodle has a newer version of.
     */
    suspend fun openAttachment(file: MoodleFile): MoodleAttachment {
        if (!MoodleFileUrls.isMoodleFile(file.url)) return MoodleAttachment.Link(file.url)
        if (file.size > MAX_DOWNLOAD_BYTES) {
            return MoodleAttachment.Link(MoodleFileUrls.webUrl(file.url) ?: file.url)
        }
        val key = slotName(file)
        // Taken before anything suspends, so a second tap while the first downloads joins it
        // rather than writing the same file beside it. In the store's scope: leaving the screen
        // does not stop a download, a sign-out does.
        val opening = synchronized(openings) {
            openings[key]?.takeIf { it.isActive } ?: scope.async { openNow(file, key) }.also { openings[key] = it }
        }
        return opening.await().also {
            synchronized(openings) { if (openings[key] === opening) openings.remove(key) }
        }
    }

    private val openings = HashMap<String, Deferred<MoodleAttachment>>()

    private suspend fun openNow(file: MoodleFile, key: String): MoodleAttachment {
        val dir = withContext(Dispatchers.IO) { openedFilesDir?.invoke() }
            ?: return MoodleAttachment.Failed(IllegalStateException("no folder for files"))
        val slot = File(dir, key)
        val target = File(slot, safeFileName(file.name))
        val trusted = file.checkableMimeType() != null
        val kept = withContext(Dispatchers.IO) {
            // Dated now, before anything is fetched, so a prune from another opening keeps it.
            slot.mkdirs()
            slot.setLastModified(System.currentTimeMillis())
            pruneOpened(dir, keep = slot)
            trusted && target.isFile
        }
        if (kept) return MoodleAttachment.Local(target)
        _downloading.update { it + file.url }
        var partial: File? = null
        return try {
            val part = withContext(Dispatchers.IO) { File.createTempFile(".dl", ".part", slot) }
            partial = part
            api.download(file, part, maxBytes = if (file.size > 0) file.size else MAX_DOWNLOAD_BYTES)
            withContext(Dispatchers.IO) {
                target.delete()
                if (!part.renameTo(target)) throw java.io.IOException("could not keep the file")
            }
            if (context.isCurrent()) {
                MoodleAttachment.Local(target)
            } else {
                // Landed after a sign-out: the next account must not find it.
                withContext(NonCancellable + Dispatchers.IO) { slot.deleteRecursively() }
                MoodleAttachment.Dropped
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "attachment not downloaded: ${e.logSummary()}")
            MoodleAttachment.Failed(e)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                partial?.delete()
                if (slot.list().isNullOrEmpty()) slot.delete()
            }
            _downloading.update { it - file.url }
        }
    }

    /** Copies opened more than a day ago, which another app had its chance to read. */
    private fun pruneOpened(dir: File, keep: File) {
        val cutoff = System.currentTimeMillis() - DAY_MS
        dir.listFiles()?.filter { it != keep && it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
    }

    /** One folder per file: its link, place and version, so neither a namesake nor an update shares it. */
    private fun slotName(file: MoodleFile): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest("${file.url}|${file.path.orEmpty()}|${file.modified ?: 0}".toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }

    /**
     * [name] as one file name: no slash, backslash or NUL, no leading dot to hide or climb with, and
     * at most [MAX_NAME_BYTES] bytes, its extension kept, where a file system allows 255.
     */
    private fun safeFileName(name: String): String {
        val cleaned = name.replace(Regex("""[/\\\u0000]"""), "_").trim().trimStart('.').ifEmpty { "file" }
        if (cleaned.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES) return cleaned
        val dot = cleaned.lastIndexOf('.')
        val extension = if (dot > 0 && cleaned.length - dot <= 16) cleaned.substring(dot) else ""
        val budget = MAX_NAME_BYTES - extension.toByteArray(Charsets.UTF_8).size
        val stem = StringBuilder()
        var used = 0
        var i = 0
        val body = cleaned.substring(0, cleaned.length - extension.length)
        while (i < body.length) {
            val codePoint = body.codePointAt(i)
            val bytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            if (used + bytes > budget) break
            stem.appendCodePoint(codePoint)
            used += bytes
            i += Character.charCount(codePoint)
        }
        return stem.toString().ifEmpty { "file" } + extension
    }

    // --- Pictures ---

    private val _pictures = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())

    /** Members' own pictures by URL, decoded once per store. */
    val pictures: StateFlow<Map<String, ImageBitmap>> = _pictures.asStateFlow()

    /** One download at a time, in the order rows ask, so the pictures on screen come first. */
    private val pictureTurn = Mutex()

    /**
     * A member's own picture, from the device, else from Moodle once and then kept with the
     * account's other Moodle files. One who keeps Moodle's default picture, or whose download
     * fails or lands after a sign-out, keeps their initial.
     */
    suspend fun loadPicture(member: MoodleMember) {
        val url = member.pictureUrl ?: return
        if (url in _pictures.value) return
        val file = pictureFile(member.id, url)
        val local = file?.let { f ->
            withContext(Dispatchers.IO) { runCatching { if (f.exists()) f.readBytes() else null }.getOrNull() }
        }
        local?.let { bytes -> withContext(background) { decodePicture(bytes) } }?.let { image ->
            _pictures.update { it + (url to image) }
            return
        }
        val bytes = pictureTurn.withLock {
            if (url in _pictures.value) return
            try {
                api.picture(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "member picture not downloaded: ${e.logSummary()}")
                null
            }
        } ?: return
        val image = withContext(background) { decodePicture(bytes) } ?: return
        if (!context.isCurrent()) return
        file?.let { savePicture(bytes, it) }
        _pictures.update { it + (url to image) }
    }

    /** One file per member and picture revision, which a new upload changes. */
    private fun pictureFile(memberId: Int, url: String): File? {
        val dir = picturesDir ?: return null
        val revision = Regex("[?&]rev=([^&]+)").find(url)?.groupValues?.get(1)
            ?.filter { it.isLetterOrDigit() }.orEmpty()
        return File(dir, "$memberId-$revision")
    }

    /** Replaces the member's earlier pictures. */
    private suspend fun savePicture(bytes: ByteArray, file: File) = withContext(Dispatchers.IO) {
        runCatching {
            val dir = file.parentFile ?: return@runCatching
            dir.mkdirs()
            val prefix = file.name.substringBefore('-') + "-"
            dir.listFiles { _, name -> name.startsWith(prefix) }?.forEach { it.delete() }
            file.writeBytes(bytes)
        }
    }

    companion object {
        private const val TAG = "MoodleCourseStore"

        /** The largest attachment downloaded into the app; a larger one opens on the website. */
        const val MAX_DOWNLOAD_BYTES = 100L * 1024 * 1024

        private const val DAY_MS = 24 * 60 * 60 * 1000L

        /** Under the 255 bytes most file systems allow a name, with room for the partial file's. */
        private const val MAX_NAME_BYTES = 200

        /** How many pages an announcement opened cold is looked for in. */
        private const val MAX_LOOKUP_PAGES = 10

        val FORUM_FUNCTIONS = setOf(MoodleCourseApi.FORUMS_FUNCTION, MoodleCourseApi.DISCUSSIONS_FUNCTION)

        /**
         * The codes a course's Moodle summary is checked against: every idnumber the Moodle course
         * [courseId] goes by in [idMap], since a co-listed course answers to each department's, the
         * course number in each, and [courseNo] from the class table.
         */
        fun courseCodes(courseId: Int, courseNo: String?, idMap: Map<String, Int>): List<String> {
            val idnumbers = idMap.filterValues { it == courseId }.keys.toList()
            return idnumbers + idnumbers.map { it.drop(4) } + listOfNotNull(courseNo)
        }

        /** Every Moodle course of the term [courseId] belongs to, by the idnumbers' term prefix. */
        fun termCourseIds(courseId: Int, idMap: Map<String, Int>): List<Int> {
            val terms = idMap.filterValues { it == courseId }.keys
                .mapNotNull(org.ntust.app.tigerduck.network.MoodleCourseIds::semesterPrefix)
                .toSet()
            if (terms.isEmpty()) return listOf(courseId)
            return idMap.filterKeys { org.ntust.app.tigerduck.network.MoodleCourseIds.semesterPrefix(it) in terms }
                .values.distinct().sorted()
        }
    }
}

/** An attachment as it opens: the copy in the app, its own link off the Moodle site, or why neither. */
sealed interface MoodleAttachment {
    data class Local(val file: File) : MoodleAttachment
    data class Link(val url: String) : MoodleAttachment
    data class Failed(val error: Throwable) : MoodleAttachment

    /** Landed after the account signed out, and deleted: there is nothing to show. */
    data object Dropped : MoodleAttachment
}

/** What a page opened with nothing cached found: what it shows, nothing (it is gone), or why not. */
sealed interface MoodleLookup {
    data object Found : MoodleLookup
    data object Missing : MoodleLookup
    data class Failed(val error: Throwable) : MoodleLookup
}
