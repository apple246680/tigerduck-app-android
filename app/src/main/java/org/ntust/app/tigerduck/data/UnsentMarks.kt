package org.ntust.app.tigerduck.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.util.logSummary
import javax.inject.Inject
import javax.inject.Singleton

/** Sends one assignment's mark; see [PushApiClient.patchAssignmentOverride]. */
internal fun interface MarkSender {
    suspend fun send(assignmentId: Int, status: String)
}

/**
 * Done and ignored marks tapped here that the backend has not confirmed, by
 * assignment id, with the status the tap set.
 *
 * A failed PATCH used to be only logged. Once the account's marks had been
 * through a sync here, the next one took the server — which never got the
 * mark — as the truth and wiped it (tigerduck-app-android#155 made an empty
 * server the truth). Now the mark waits on disk: a full sync keeps this
 * phone's value for it and sends it again first, until it lands. The iPhone
 * app's outbox does the same.
 *
 * Nothing goes out, and a new tap is not kept, while [sends] is false: with
 * the Assignments switch off the marks are this phone's own, and while the
 * assignments re-enable check is pending the user's answer decides them. A
 * mark already waiting stays until marks are sent again, or until the server's
 * marks are taken over this phone's ([forget]). Account-scoped: wiped with the
 * user data at sign-out.
 */
@Singleton
class UnsentMarks internal constructor(
    private val dataCache: DataCache,
    private val sender: MarkSender,
    private val sends: () -> Boolean,
) {
    @Inject constructor(
        dataCache: DataCache,
        pushApiClient: PushApiClient,
        prefs: AppPreferences,
    ) : this(
        dataCache = dataCache,
        sender = MarkSender(pushApiClient::patchAssignmentOverride),
        sends = { sendsMarks(prefs) },
    )

    private val mutex = Mutex()

    // One PATCH at a time, so marks reach the backend in the order they were
    // made: the backend keeps whichever request lands last.
    private val sendMutex = Mutex()

    /** The ids whose last tap has not reached the server; a sync keeps this phone's value for them. */
    suspend fun ids(): Set<String> = dataCache.loadUnsentMarks().keys

    /** A tap that set [assignmentId] to [status]: sent at once, and kept until the server has it. */
    suspend fun send(assignmentId: String, status: String) {
        if (!sends()) return
        val generation = dataCache.userDataGeneration
        val kept = mutex.withLock {
            dataCache.saveUnsentMarks(dataCache.loadUnsentMarks() + (assignmentId to status), generation)
        }
        if (kept) trySend(assignmentId, status, generation)
    }

    /**
     * Keeps a mark Use Local could not send, whatever [sends] says: while the
     * answer is applied the re-enable check still holds marks, and dropped,
     * the mark would be lost to the next sync. Written only while the data is
     * still [generation]'s.
     */
    suspend fun keep(assignmentId: String, status: String, generation: Int) {
        mutex.withLock {
            dataCache.saveUnsentMarks(dataCache.loadUnsentMarks() + (assignmentId to status), generation)
        }
    }

    /** Sends every mark still waiting; each full sync starts with this. */
    suspend fun retry() {
        if (!sends()) return
        val generation = dataCache.userDataGeneration
        for ((id, status) in dataCache.loadUnsentMarks()) trySend(id, status, generation)
    }

    /** The server's marks were taken over this phone's, so what waited is no longer wanted. */
    suspend fun forget() {
        mutex.withLock { dataCache.saveUnsentMarks(emptyMap()) }
    }

    private suspend fun trySend(id: String, status: String, generation: Int) {
        val intId = id.toIntOrNull()
        if (intId != null) {
            try {
                sendMutex.withLock { sender.send(intId, status) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "mark PATCH failed, kept for the next sync: ${e.logSummary()}")
                return
            }
        }
        // Only if no newer tap of the id replaced it meanwhile.
        mutex.withLock {
            val waiting = dataCache.loadUnsentMarks()
            if (waiting[id] == status) dataCache.saveUnsentMarks(waiting - id, generation)
        }
    }

    companion object {
        private const val TAG = "UnsentMarks"

        /** The re-enable conflict category the marks belong to. */
        private const val REENABLE_CATEGORY = "assignments"

        /** Whether marks go out: cloud and assignment sync on, and no assignments re-enable check pending. */
        fun sendsMarks(prefs: AppPreferences): Boolean =
            prefs.cloudSyncEnabled && prefs.syncAssignments &&
                REENABLE_CATEGORY !in prefs.pendingConflictCategories
    }
}
