package org.ntust.app.tigerduck.data.moodle

import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
    private val decodePicture: (ByteArray) -> ImageBitmap? = { null },
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
     * the course's forums, then its announcements, each fetched only when the cache lacks it.
     */
    suspend fun ensureAnnouncement(discussionId: Int) {
        awaitCache()
        if (currentNewsForum() == null) refreshForums()
        val pages = announcementPages() ?: return
        pages.feed.awaitCache()
        val listed = pages.feed.value?.takeIf { it.forumId == pages.forumId }?.discussions.orEmpty()
        if (listed.none { it.id == discussionId }) pages.refresh()
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
    suspend fun ensureMember(memberId: Int) {
        awaitCache()
        if (member(memberId) == null) refreshMembers()
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

    /** Where an attachment opens from outside the app; null when it cannot be opened now. */
    suspend fun fileUrl(url: String): String? = try {
        api.fileUrl(url)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "file url not resolved: ${e.logSummary()}")
        null
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
        local?.let(decodePicture)?.let { image ->
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
        val image = decodePicture(bytes) ?: return
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
