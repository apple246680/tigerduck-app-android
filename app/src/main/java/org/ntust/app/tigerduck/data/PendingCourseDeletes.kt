package org.ntust.app.tigerduck.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.util.logSummary
import javax.inject.Inject
import javax.inject.Singleton

/** Deletes one course's row from the backend by its key; see [PushApiClient.deleteCourse]. */
internal fun interface CourseDeleter {
    suspend fun delete(courseKey: String)
}

/**
 * Courses deleted on the class table whose backend DELETE has not landed,
 * by term.
 *
 * A delete went once. A course taken back out of Add course
 * (`ClassTableViewModel.removeAddedCourse`) writes no tombstone here, so a
 * failed DELETE left its row on the server for the next full sync to merge
 * back as a hand-added course; a regular delete's tombstone did not help
 * either, as the reconcile un-hides a course the server still lists. Now the
 * delete waits on disk, kept before the request so a process killed during
 * it keeps it too, every full sync sends it again first, as [PendingCourseAdds]
 * does for adds, and the reconcile keeps the course out of the term until it
 * lands (`CourseSyncReconciler.reconcileSemester`'s `graceCourseNos`). It goes
 * once the DELETE lands, once the course is added back (the add's force key
 * would otherwise lose to a DELETE landing after it), or once its term is
 * reset, which deletes the term's rows itself. Account-scoped: kept with the
 * user data and wiped with it at sign-out.
 *
 * The iPhone's `CloudSyncCoordinator` queues its course deletes in its outbox
 * the same way and hides them with `pendingCourseDeleteKeys` meanwhile.
 */
@Singleton
class PendingCourseDeletes internal constructor(
    private val dataCache: DataCache,
    private val deleter: CourseDeleter,
) {
    @Inject constructor(dataCache: DataCache, pushApiClient: PushApiClient) : this(
        dataCache = dataCache,
        deleter = CourseDeleter(pushApiClient::deleteCourse),
    )

    private val mutex = Mutex()

    /**
     * Deletes [courseNo]'s row in [semester] from the backend, keeping the
     * delete until it lands. Call it with no add being sent meanwhile; see
     * [PendingCourseAdds.removing].
     */
    suspend fun send(semester: String, courseNo: String) {
        val generation = dataCache.userDataGeneration
        if (!edit(generation) { it.with(semester, courseNo) }) return
        trySend(semester, courseNo, generation)
    }

    /** Sends every delete still waiting; each full sync starts with this, so the snapshot lacks them. */
    suspend fun retry() {
        val generation = dataCache.userDataGeneration
        for ((semester, nos) in waiting()) {
            for (courseNo in nos) {
                if (!trySend(semester, courseNo, generation)) return
            }
        }
    }

    /** The course numbers whose delete in [semester] is still waiting, for the reconcile to keep out. */
    suspend fun waitingIn(semester: String): Set<String> = waiting()[semester].orEmpty().toSet()

    /** Lets go of [courseNo]'s delete in [semester]: the course was added back. */
    suspend fun forget(semester: String, courseNo: String) {
        edit(dataCache.userDataGeneration) { it.without(semester, listOf(courseNo)) }
    }

    /** Lets go of every delete in [semester]: the term was reset, which deleted its rows. */
    suspend fun forget(semester: String) {
        edit(dataCache.userDataGeneration) { it - semester }
    }

    // False once the data was wiped since [generation]: the delete was read as
    // another account's and is not sent as this one's.
    private suspend fun trySend(semester: String, courseNo: String, generation: Int): Boolean {
        if (generation != dataCache.userDataGeneration) return false
        try {
            deleter.delete(PendingCourseAdds.forceKey(semester, courseNo))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "course delete failed, kept for the next sync: ${e.logSummary()}")
            return true
        }
        edit(generation) { it.without(semester, listOf(courseNo)) }
        return true
    }

    private suspend fun waiting(): Map<String, List<String>> = dataCache.loadPendingCourseDeletes()

    // False, and nothing written, once the data was wiped since [generation].
    private suspend fun edit(
        generation: Int,
        change: (Map<String, List<String>>) -> Map<String, List<String>>,
    ): Boolean = mutex.withLock {
        val before = waiting()
        val after = change(before)
        after == before || dataCache.savePendingCourseDeletes(after, generation)
    }

    private fun Map<String, List<String>>.with(semester: String, courseNo: String) =
        this + (semester to (this[semester].orEmpty() + courseNo).distinct())

    private fun Map<String, List<String>>.without(semester: String, courseNos: Collection<String>): Map<String, List<String>> {
        val kept = this[semester].orEmpty() - courseNos.toSet()
        return if (kept.isEmpty()) this - semester else this + (semester to kept)
    }

    companion object {
        private const val TAG = "PendingCourseDeletes"
    }
}
