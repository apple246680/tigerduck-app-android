package org.ntust.app.tigerduck.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.push.ServerCourse
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.logSummary
import javax.inject.Inject
import javax.inject.Singleton

/** Uploads one term's courses; see [PushApiClient.uploadCourses]. */
internal fun interface CourseUploader {
    suspend fun upload(courses: List<Course>, semester: String, forceKeys: List<String>): List<String>?
}

/**
 * Courses added by hand on the class table whose upload the backend has not
 * taken, by term.
 *
 * An add went up once. A sync re-uploads a term's courses only when the
 * server has none for it (`CourseSyncReconciler.reconcileSemester`), so in a
 * term the server already had, a failed upload left the course on this phone
 * alone: never on another device, and lost at sign-out. Now the add waits on
 * disk, kept before the request so a process killed during it keeps it too,
 * and every full sync sends it again first with the same force key, as
 * [UnsentMarks] does for marks. It goes once an upload lands or a snapshot
 * lists the course, or once the course has left this phone's cache (deleted
 * by hand, a reset, Use Server). Account-scoped: wiped with the user data at
 * sign-out.
 *
 * iOS sends an add once and keeps nothing; its next sync drops a hand-added
 * course a populated server term does not list.
 */
@Singleton
class PendingCourseAdds internal constructor(
    private val dataCache: DataCache,
    private val uploader: CourseUploader,
) {
    @Inject constructor(dataCache: DataCache, pushApiClient: PushApiClient) : this(
        dataCache = dataCache,
        uploader = CourseUploader(pushApiClient::uploadCourses),
    )

    private val mutex = Mutex()

    /**
     * Uploads [courses], [semester]'s list with [courseNo] just added to it,
     * forcing [courseNo] past a tombstone as an add does. Kept until it lands.
     * Let go as well when nothing was sent because course sync is off: the
     * re-enable check answers for the courses when it comes back on.
     */
    suspend fun send(courses: List<Course>, semester: String, courseNo: String) {
        val generation = dataCache.userDataGeneration
        if (!edit(generation) { it.with(semester, listOf(courseNo)) }) return
        if (upload(courses, semester, listOf(courseNo))) edit(generation) { it.without(semester, listOf(courseNo)) }
    }

    /**
     * Sends every add still waiting, with the course as this phone has it
     * now; each full sync starts with this, so the snapshot it fetches has
     * them. An add whose course this phone no longer holds is let go unsent.
     */
    suspend fun retry() {
        val generation = dataCache.userDataGeneration
        for ((semester, nos) in waiting()) {
            val held = dataCache.loadCourses(semester).filter { it.courseNo in nos }
            val gone = nos - held.map { it.courseNo }.toSet()
            val sent = held.isNotEmpty() && upload(held, semester, held.map { it.courseNo })
            val done = if (sent) nos else gone
            if (done.isNotEmpty()) edit(generation) { it.without(semester, done) }
        }
    }

    /** Lets go of the adds [serverCourses], a snapshot's rows, list: the server has them. */
    suspend fun listed(serverCourses: List<ServerCourse>) {
        val generation = dataCache.userDataGeneration
        val waiting = waiting()
        if (waiting.isEmpty()) return
        val listed = serverCourses.groupBy({ it.semester }, { it.courseNo })
        if (waiting.none { (semester, nos) -> listed[semester].orEmpty().any { it in nos } }) return
        edit(generation) { pending ->
            listed.entries.fold(pending) { acc, (semester, nos) -> acc.without(semester, nos) }
        }
    }

    // Whether the request went through: sent, or nothing to send with course sync off.
    private suspend fun upload(courses: List<Course>, semester: String, courseNos: List<String>): Boolean =
        try {
            uploader.upload(courses, semester, courseNos.map { forceKey(semester, it) })
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "course add upload failed, kept for the next sync: ${e.logSummary()}")
            false
        }

    private suspend fun waiting(): Map<String, List<String>> = dataCache.loadPendingCourseAdds()

    // False, and nothing written, once the data was wiped since [generation].
    private suspend fun edit(
        generation: Int,
        change: (Map<String, List<String>>) -> Map<String, List<String>>,
    ): Boolean = mutex.withLock {
        val before = waiting()
        val after = change(before)
        after == before || dataCache.savePendingCourseAdds(after, generation)
    }

    private fun Map<String, List<String>>.with(semester: String, courseNos: List<String>) =
        this + (semester to (this[semester].orEmpty() + courseNos).distinct())

    private fun Map<String, List<String>>.without(semester: String, courseNos: Collection<String>): Map<String, List<String>> {
        val kept = this[semester].orEmpty() - courseNos.toSet()
        return if (kept.isEmpty()) this - semester else this + (semester to kept)
    }

    companion object {
        private const val TAG = "PendingCourseAdds"

        /** The key an upload forces past a tombstone, as the class table's add sends it. */
        fun forceKey(semester: String, courseNo: String): String = "client:$semester:$courseNo"
    }
}
