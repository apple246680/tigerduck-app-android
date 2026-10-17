package org.ntust.app.tigerduck.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.PendingCourseOverride
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.util.logSummary
import javax.inject.Inject
import javax.inject.Singleton

/** Sends one course's colour, or its name in one locale, as sign-in [signIn]; see [PushApiClient.patchCourseOverride]. */
internal fun interface CourseOverrideSender {
    suspend fun send(moodleId: String, colorHex: String?, customName: String?, locale: String?, signIn: Int): Boolean
}

/**
 * Colours and names set on the class table that the backend has not
 * confirmed, by course and, for a name, locale.
 *
 * A change went up once. Failed, as it does offline, it was only logged: the
 * colour or name stayed on this phone alone, never on another device, and
 * lost at sign-out (tigerduck-app-android#380). Now the change waits on disk,
 * kept before the request so a process killed during it keeps it too, and
 * every full sync sends what waits first, as [PendingCourseAdds] does for
 * hand-added courses. A later change of the same colour, or of the same
 * name, replaces the one waiting, so the older never goes up over it. A
 * change goes once the backend confirms it, or once nothing was sent for it
 * because TigerSync or its switch is off: the re-enable check answers for
 * the colours and names when the switch comes back on. Account-scoped: sent
 * only as the account that made it, and wiped with the user data at
 * sign-out.
 *
 * A snapshot fetched before a change, or before its PATCH landed, still has
 * the old value; [held] names what a sync must not take from it.
 */
@Singleton
class PendingCourseOverrides internal constructor(
    private val dataCache: DataCache,
    private val sender: CourseOverrideSender,
    private val signInGeneration: () -> Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Inject constructor(dataCache: DataCache, pushApiClient: PushApiClient, authTokenManager: AuthTokenManager) : this(
        dataCache = dataCache,
        sender = CourseOverrideSender { moodleId, colorHex, customName, locale, signIn ->
            pushApiClient.patchCourseOverride(moodleId, colorHex = colorHex, customName = customName, locale = locale, signIn = signIn)
        },
        signInGeneration = { authTokenManager.signInGeneration },
    )

    private val mutex = Mutex()

    // One PATCH at a time, so changes reach the backend in the order they
    // were made: the backend keeps whichever request lands last.
    private val sendMutex = Mutex()

    // When each key last changed here or was confirmed by the backend, under
    // [mutex]. A snapshot fetched before then may predate the change, so its
    // value for the key is not taken. In memory: only a sync running in this
    // process can be that old.
    private val touchedAt = HashMap<String, Long>()

    /** Whether a colour or name set here has yet to reach the backend: data a sign-out would lose. */
    suspend fun hasPending(): Boolean = waiting().isNotEmpty()

    /**
     * Keeps the colour [colorHex], or the name [customName] in [locale], set
     * for the course the backend knows as [moodleId], in place of any older
     * change of the same, and sends it. Called in the order the changes were
     * made; nothing to do without a colour or a name.
     */
    suspend fun send(moodleId: String, colorHex: String? = null, customName: String? = null, locale: String? = null) {
        val generation = dataCache.userDataGeneration
        val signIn = signInGeneration()
        val change = PendingCourseOverride(moodleId, colorHex, customName, locale, clock())
        val key = change.key ?: return
        val kept = mutex.withLock {
            if (!save(waiting() + (key to change), generation)) return@withLock false
            touchedAt[key] = change.changedAtMs
            true
        }
        if (kept) trySend(key, change, generation, signIn)
    }

    /** Sends every change still waiting; each full sync starts with this, so the snapshot it fetches has them. */
    suspend fun retry() {
        val generation = dataCache.userDataGeneration
        val signIn = signInGeneration()
        for ((key, change) in waiting()) trySend(key, change, generation, signIn)
    }

    /**
     * The keys ([PendingCourseOverride.key]) a snapshot fetched at
     * [fetchedAtMs] must not overwrite: every change still waiting, and
     * every one made or confirmed since the fetch, which the snapshot
     * predates.
     */
    suspend fun held(fetchedAtMs: Long): Set<String> = mutex.withLock {
        waiting().keys + touchedAt.filterValues { it >= fetchedAtMs }.keys
    }

    /**
     * Forgets what waits for the courses [moodleIds]: deleted by hand, or
     * their term reset, and the backend's override went with the course row.
     * Sent later, a change would set its colour or name on the row a re-add
     * or an upload makes anew.
     */
    suspend fun forget(moodleIds: Collection<String>) {
        if (moodleIds.isEmpty()) return
        val generation = dataCache.userDataGeneration
        mutex.withLock {
            val waiting = waiting()
            val kept = waiting.filterValues { it.moodleId !in moodleIds }
            if (kept.size != waiting.size) save(kept, generation)
        }
    }

    /**
     * Sends [change] if it is still the change waiting under [key], for the
     * account whose data [generation] and sign-in [signIn] were read with.
     * Checked under the send lock, right before the request: a newer change
     * of the key, a send of it that landed meanwhile, or a sign-out each make
     * it stale, and sent it would undo the newer change, repeat the request,
     * or land on the next account. The request itself carries [signIn], so a
     * sign-out after the check sends it without a token rather than with the
     * next account's. Let go once confirmed, or once nothing was sent because
     * the switch is off, unless a newer change replaced it meanwhile.
     */
    private suspend fun trySend(key: String, change: PendingCourseOverride, generation: Int, signIn: Int) {
        val moodleId = change.moodleId ?: return
        val sent = sendMutex.withLock {
            val current = mutex.withLock {
                generation == dataCache.userDataGeneration && signIn == signInGeneration() && waiting()[key] == change
            }
            if (!current) return
            try {
                sender.send(moodleId, change.colorHex, change.customName, change.locale, signIn)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "course override PATCH failed, kept for the next sync: ${e.logSummary()}")
                return
            }
        }
        mutex.withLock {
            val waiting = waiting()
            if (waiting[key] != change) return@withLock
            if (save(waiting - key, generation) && sent) touchedAt[key] = clock()
        }
    }

    // By key, in the order the changes were made.
    private suspend fun waiting(): Map<String, PendingCourseOverride> =
        dataCache.loadPendingCourseOverrides().associateByTo(LinkedHashMap()) { it.key.orEmpty() }

    private suspend fun save(waiting: Map<String, PendingCourseOverride>, generation: Int): Boolean =
        dataCache.savePendingCourseOverrides(waiting.values.toList(), generation)

    companion object {
        private const val TAG = "PendingCourseOverrides"
    }
}
