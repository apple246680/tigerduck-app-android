package org.ntust.app.tigerduck.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.util.logSummary
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Deletes one course's row from the backend by its key, as sign-in [signIn]; see [PushApiClient.deleteCourse]. */
internal fun interface CourseDeleter {
    suspend fun delete(courseKey: String, signIn: Int)
}

/**
 * Courses deleted on the class table whose backend DELETE has not landed,
 * by term, each with the moment it was asked ([stamp]).
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
 * [PendingCourseAdds.removing], queues the delete under it and checks right
 * before the request that the delete is still queued: a retry that read a
 * delete, then waited behind a re-add's upload or a reset, must not take the
 * restored course off the server afterwards. So the re-add's [forget] takes
 * that lock too, and a reset calls [forgetTerm] inside the lock its wipe
 * holds. A re-add and a removal compare by when each was asked, not by when
 * each reached the lock: [forget] lets go only of a delete asked before the
 * re-add, so a course removed again while its first DELETE is still under
 * way stays queued once that DELETE fails.
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
    private val clock: () -> Long = System::currentTimeMillis,
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

    private val lastStamp = AtomicLong(0)

    /**
     * The moment of a class-table action, for [send] and [forget]: the clock,
     * and later than every earlier one's whatever the clock does. Take it as
     * the user acts, before the work that follows suspends, so a re-add and a
     * removal compare by the order they were asked in, not by when each
     * reached the lock behind a slow request.
     */
    fun stamp(): Long = lastStamp.updateAndGet { last -> maxOf(last + 1, clock()) }

    /**
     * Deletes [courseNo]'s row in [semester] from the backend, keeping the
     * delete, asked at [queuedAt], until it lands. Takes
     * [PendingCourseAdds.removing] itself, so call it outside that lock.
     */
    suspend fun send(semester: String, courseNo: String, queuedAt: Long = stamp()) {
        val generation = dataCache.userDataGeneration
        val signIn = signInGeneration()
        pendingCourseAdds.removing {
            // Queued under the lock, with the request, not before it: a
            // re-add's forget waiting for the lock ahead of this send would
            // otherwise find the delete already there and let it go.
            if (!edit(generation) { it.with(semester, courseNo, queuedAt) }) return@removing
            sendHeld(semester, courseNo, generation, signIn)
        }
    }

    /**
     * Sends every delete still waiting, each under the lock on its own, so an
     * add or a reset can come between two; each full sync starts with this,
     * so the snapshot lacks them.
     */
    suspend fun retry() {
        val generation = dataCache.userDataGeneration
        val signIn = signInGeneration()
        for ((semester, entries) in waiting()) {
            for (courseNo in entries.keys) {
                val goOn = pendingCourseAdds.removing { sendHeld(semester, courseNo, generation, signIn) }
                if (!goOn) return
            }
        }
    }

    /** The course numbers whose delete in [semester] is still waiting, for the reconcile to keep out. */
    suspend fun waitingIn(semester: String): Set<String> = waiting()[semester].orEmpty().keys

    /**
     * Lets go of [courseNo]'s delete in [semester] asked before [askedAt], the
     * moment the course was added back: one asked after the re-add is a newer
     * removal and stays. Under the adds lock, after any send of it under way:
     * a retry that read it is then either done or finds it gone. Call it
     * outside that lock, before the add's own upload.
     */
    suspend fun forget(semester: String, courseNo: String, askedAt: Long = stamp()) {
        val generation = dataCache.userDataGeneration
        pendingCourseAdds.removing {
            edit(generation) { it.without(semester, courseNo) { queuedAt -> queuedAt <= askedAt } }
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
    private suspend fun sendHeld(semester: String, courseNo: String, generation: Int, signIn: Int): Boolean {
        if (generation != dataCache.userDataGeneration || signIn != signInGeneration()) return false
        // Only a delete still queued: one a re-add or a reset let go
        // meanwhile must not go, as it would take the restored course off.
        val queuedAt = waiting()[semester]?.get(courseNo) ?: return true
        try {
            // The request itself carries [signIn]: a sign-out after the
            // check sends it without a token rather than with the next
            // account's.
            deleter.delete(PendingCourseAdds.forceKey(semester, courseNo), signIn)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "course delete failed, kept for the next sync: ${e.logSummary()}")
            return true
        }
        // The delete sent; nothing queues under the lock meanwhile.
        edit(generation) { it.without(semester, courseNo) { it == queuedAt } }
        return true
    }

    private suspend fun waiting(): Map<String, Map<String, Long>> = dataCache.loadPendingCourseDeletes()

    // False, and nothing written, once the data was wiped since [generation].
    private suspend fun edit(
        generation: Int,
        change: (Map<String, Map<String, Long>>) -> Map<String, Map<String, Long>>,
    ): Boolean = mutex.withLock {
        val before = waiting()
        val after = change(before)
        after == before || dataCache.savePendingCourseDeletes(after, generation)
    }

    // A delete asked again keeps the later moment: it is the newer removal.
    private fun Map<String, Map<String, Long>>.with(semester: String, courseNo: String, queuedAt: Long) =
        this + (semester to this[semester].orEmpty().let { it + (courseNo to maxOf(it[courseNo] ?: 0L, queuedAt)) })

    private fun Map<String, Map<String, Long>>.without(
        semester: String,
        courseNo: String,
        asked: (queuedAt: Long) -> Boolean,
    ): Map<String, Map<String, Long>> {
        val entries = this[semester].orEmpty()
        val queuedAt = entries[courseNo] ?: return this
        if (!asked(queuedAt)) return this
        val kept = entries - courseNo
        return if (kept.isEmpty()) this - semester else this + (semester to kept)
    }

    companion object {
        private const val TAG = "PendingCourseDeletes"
    }
}
