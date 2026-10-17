package org.ntust.app.tigerduck.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.util.logSummary
import javax.inject.Inject
import javax.inject.Singleton

/** Deletes one course's row from the backend by its key, as sign-in [signIn]; see [PushApiClient.deleteCourse]. */
internal fun interface CourseDeleter {
    suspend fun delete(courseKey: String, signIn: Int)
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
 * user data and wiped with it at sign-out, and each request pinned to the
 * sign-in it was read under, as the adds are, so a sign-out and another
 * sign-in while it waits for its header cannot send it as the next account.
 *
 * Every send holds the lock the adds and term resets take,
 * [PendingCourseAdds.removing], and checks right before the request that the
 * delete is still queued: a retry that read a delete, then waited behind a
 * re-add's upload or a reset, must not take the restored course off the
 * server afterwards. So the re-add's [forget] takes that lock too, and a
 * reset calls [forgetTerm] inside the lock its wipe holds.
 *
 * The iPhone's `CloudSyncCoordinator` queues its course deletes in its outbox
 * the same way, hides them with `pendingCourseDeleteKeys` meanwhile and drops
 * them with `discardCourseDeletes` on a re-add or a reset.
 */
@Singleton
class PendingCourseDeletes internal constructor(
    private val dataCache: DataCache,
    private val pendingCourseAdds: PendingCourseAdds,
    private val deleter: CourseDeleter,
    private val signInGeneration: () -> Int,
) {
    @Inject constructor(
        dataCache: DataCache,
        pendingCourseAdds: PendingCourseAdds,
        pushApiClient: PushApiClient,
        authTokenManager: AuthTokenManager,
    ) : this(
        dataCache = dataCache,
        pendingCourseAdds = pendingCourseAdds,
        deleter = CourseDeleter(pushApiClient::deleteCourse),
        signInGeneration = { authTokenManager.signInGeneration },
    )

    private val mutex = Mutex()

    /**
     * Deletes [courseNo]'s row in [semester] from the backend, keeping the
     * delete until it lands. Takes [PendingCourseAdds.removing] itself, so
     * call it outside that lock.
     */
    suspend fun send(semester: String, courseNo: String) {
        val generation = dataCache.userDataGeneration
        val signIn = signInGeneration()
        if (!edit(generation) { it.with(semester, courseNo) }) return
        trySend(semester, courseNo, generation, signIn)
    }

    /**
     * Sends every delete still waiting, each under the lock on its own, so an
     * add or a reset can come between two; each full sync starts with this,
     * so the snapshot lacks them.
     */
    suspend fun retry() {
        val generation = dataCache.userDataGeneration
        val signIn = signInGeneration()
        for ((semester, nos) in waiting()) {
            for (courseNo in nos) {
                if (!trySend(semester, courseNo, generation, signIn)) return
            }
        }
    }

    /** The course numbers whose delete in [semester] is still waiting, for the reconcile to keep out. */
    suspend fun waitingIn(semester: String): Set<String> = waiting()[semester].orEmpty().toSet()

    /**
     * Lets go of [courseNo]'s delete in [semester]: the course is being added
     * back. Under the adds lock, after any send of it under way: a retry that
     * read it is then either done or finds it gone. Call it outside that lock,
     * before the add's own upload.
     */
    suspend fun forget(semester: String, courseNo: String) {
        val generation = dataCache.userDataGeneration
        pendingCourseAdds.removing {
            edit(generation) { it.without(semester, listOf(courseNo)) }
        }
    }

    /**
     * Lets go of every delete in [semester]: the term was reset, which deleted
     * its rows. Call it inside the [PendingCourseAdds.removing] block whose
     * wipe just landed, before that lock goes: a retry waiting behind the
     * wipe would otherwise send a delete the reset's refetch then re-uploads
     * past, taking the course off the server again and tombstoning it.
     */
    suspend fun forgetTerm(semester: String) {
        edit(dataCache.userDataGeneration) { it - semester }
    }

    // Under the adds lock, with the delete read again there: false once the
    // data was wiped or the account changed since [generation] and [signIn]
    // were read, when the delete was another account's and is not sent as
    // this one's; true otherwise, sent or not.
    private suspend fun trySend(semester: String, courseNo: String, generation: Int, signIn: Int): Boolean =
        pendingCourseAdds.removing {
            if (generation != dataCache.userDataGeneration || signIn != signInGeneration()) return@removing false
            // Only a delete still queued: one a re-add or a reset let go
            // meanwhile must not go, as it would take the restored course off.
            if (courseNo !in waiting()[semester].orEmpty()) return@removing true
            try {
                // The request itself carries [signIn]: a sign-out after the
                // check sends it without a token rather than with the next
                // account's.
                deleter.delete(PendingCourseAdds.forceKey(semester, courseNo), signIn)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "course delete failed, kept for the next sync: ${e.logSummary()}")
                return@removing true
            }
            edit(generation) { it.without(semester, listOf(courseNo)) }
            true
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
