package org.ntust.app.tigerduck.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.model.PendingCourseIgnore
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.MoodleCourseIds
import org.ntust.app.tigerduck.notification.AssignmentNotificationScheduler
import org.ntust.app.tigerduck.push.BackendSyncResult
import org.ntust.app.tigerduck.push.CourseOverrideResult
import org.ntust.app.tigerduck.push.PushApiClient
import javax.inject.Inject
import javax.inject.Singleton

/** Sends one course's setting; see [PushApiClient.patchCourseIgnore]. */
internal fun interface CourseIgnoreSender {
    suspend fun send(moodleId: String, ignore: Boolean?, moodleCourseId: Int?): Boolean
}

/**
 * What follows a change to the ignored courses beyond the write: re-arming
 * reminders. The Live Update follows every write on its own; see
 * [org.ntust.app.tigerduck.liveactivity.LiveActivityManager].
 */
internal fun interface CourseIgnoreEffects {
    suspend fun changed()
}

/**
 * Every write of the ignored courses ([CourseIgnores]) and of the changes
 * waiting to reach the backend, so one lock covers both files.
 *
 * Every change waits in `pending_course_ignores.json` from the tap until a
 * PATCH the backend confirms, and is sent again before each full sync; until
 * then it keeps this device's value through a sync. It is held, not sent,
 * while cloud or assignment sync is off or an assignments re-enable conflict
 * is open, so one made offline still reaches the backend later.
 *
 * Work started for one account carries [currentGeneration] and drops its writes
 * once the user data is wiped at sign-out.
 */
@Singleton
class CourseIgnoreSync internal constructor(
    private val dataCache: DataCache,
    private val sender: CourseIgnoreSender,
    private val effects: CourseIgnoreEffects,
    private val sends: () -> Boolean,
    private val clock: () -> Long,
) {
    @Inject constructor(
        dataCache: DataCache,
        pushApiClient: PushApiClient,
        prefs: AppPreferences,
        scheduler: AssignmentNotificationScheduler,
    ) : this(
        dataCache = dataCache,
        sender = CourseIgnoreSender(pushApiClient::patchCourseIgnore),
        effects = CourseIgnoreEffects {
            if (prefs.notifyAssignments) {
                scheduler.scheduleAll(
                    dataCache.loadOutstandingAssignments(),
                    prefs.notifyAssignmentOffsets,
                )
            }
        },
        sends = {
            prefs.cloudSyncEnabled && prefs.syncAssignments &&
                REENABLE_CATEGORY !in prefs.pendingConflictCategories
        },
        clock = System::currentTimeMillis,
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

    /** Taken before fetching a snapshot that is then passed in here. */
    fun currentGeneration(): Int = dataCache.userDataGeneration

    /**
     * Ignores, or stops ignoring, every assignment of [semester]'s
     * [courseNo]: here at once, then on the backend.
     */
    suspend fun setIgnored(
        semester: String,
        courseNo: String,
        moodleId: String,
        moodleCourseId: Int?,
        ignore: Boolean,
    ) {
        val generation = currentGeneration()
        val change = PendingCourseIgnore(semester, courseNo, moodleId, moodleCourseId, ignore, clock())
        val key = CourseIgnores.key(semester, courseNo)
        val written = mutex.withLock {
            val keys = dataCache.loadIgnoredCourses()
            val pending = dataCache.loadPendingCourseIgnores()
            if (!dataCache.saveIgnoredCourses(if (ignore) keys + key else keys - key, generation)) {
                return@withLock false
            }
            dataCache.savePendingCourseIgnores(pending.with(change), generation)
            touchedAt[key] = change.changedAtMs
            true
        }
        if (!written) return
        effects.changed()
        send(change, generation)
    }

    /** Sends every change still waiting, oldest first. One that fails waits for the next sync. */
    suspend fun retryPending() {
        if (!sends()) return
        val generation = currentGeneration()
        dataCache.loadPendingCourseIgnores()
            .sortedBy { it.changedAtMs }
            .forEach { send(it, generation) }
    }

    /**
     * Brings the ignored courses in line with a full sync; see
     * [CourseIgnoreReconciler.afterSync]. Returns the new set when it
     * changed, else null.
     */
    suspend fun applySnapshot(result: BackendSyncResult, generation: Int): Set<String>? {
        val server = CourseIgnoreReconciler.serverIgnores(result.courseOverrides, result.serverCourses)
        val updated = mutex.withLock {
            val local = dataCache.loadIgnoredCourses()
            val protectedKeys = dataCache.loadPendingCourseIgnores().mapNotNullTo(mutableSetOf()) { it.key } +
                touchedAt.filterValues { it >= result.fetchedAtMs }.keys
            CourseIgnoreReconciler.afterSync(local, server, protectedKeys, applies = sends())
                ?.takeIf { it != local && dataCache.saveIgnoredCourses(it, generation) }
        }
        if (updated != null) effects.changed()
        if (server != null && sends()) fillInMoodleCourseIds(result.courseOverrides, generation)
        return updated
    }

    /** Whether this device's ignored courses match [result]'s; for the re-enable check. */
    suspend fun agreesWith(result: BackendSyncResult): Boolean = CourseIgnoreReconciler.agrees(
        dataCache.loadIgnoredCourses(),
        CourseIgnoreReconciler.serverIgnores(result.courseOverrides, result.serverCourses),
    )

    /**
     * Sends this device's set over [result]'s: what is ignored here, and an
     * end to what only the backend ignores. With no [result] to go by, only
     * the first half.
     */
    suspend fun pushLocal(result: BackendSyncResult?, generation: Int) {
        val server = result?.let {
            CourseIgnoreReconciler.serverIgnores(it.courseOverrides, it.serverCourses)
        }
        val serverRows = result?.courseOverrides.orEmpty().associateBy { o ->
            val semester = o.semester ?: return@associateBy null
            val courseNo = o.courseNo ?: return@associateBy null
            CourseIgnores.key(semester, courseNo)
        }
        mutex.withLock {
            val local = dataCache.loadIgnoredCourses()
            val ignored = if (server == null) {
                local
            } else {
                local.filterTo(mutableSetOf()) { CourseIgnores.semesterOf(it) in server.semesters }
            }
            val cleared = server?.keys.orEmpty() - local
            val now = clock()
            var pending = dataCache.loadPendingCourseIgnores()
            for (key in ignored) pending = pending.with(changeFor(key, true, serverRows[key], now))
            for (key in cleared) pending = pending.with(changeFor(key, false, serverRows[key], now))
            dataCache.savePendingCourseIgnores(pending, generation)
        }
        retryPending()
    }

    /**
     * Drops every waiting change and takes [result]'s set. Without a [result]
     * the next sync takes the backend's, since nothing is left to protect.
     */
    suspend fun adoptServer(result: BackendSyncResult?, generation: Int) {
        val server = result?.let {
            CourseIgnoreReconciler.serverIgnores(it.courseOverrides, it.serverCourses)
        }
        val updated = mutex.withLock {
            if (!dataCache.savePendingCourseIgnores(emptyList(), generation)) return@withLock null
            val local = dataCache.loadIgnoredCourses()
            CourseIgnoreReconciler.afterSync(local, server, emptySet(), applies = true)
                ?.takeIf { it != local && dataCache.saveIgnoredCourses(it, generation) }
        }
        if (updated != null) effects.changed()
    }

    /**
     * Forgets the keys [matches] picks, with any change of theirs still
     * waiting: their course was deleted, or their term reset, and the
     * backend's override went with its course row.
     */
    suspend fun forget(matches: (String) -> Boolean) {
        val generation = currentGeneration()
        val changed = mutex.withLock {
            val pending = dataCache.loadPendingCourseIgnores()
            val keptPending = pending.filterNot { it.key?.let(matches) == true }
            if (keptPending.size != pending.size) {
                dataCache.savePendingCourseIgnores(keptPending, generation)
            }
            val keys = dataCache.loadIgnoredCourses()
            val kept = keys.filterNotTo(mutableSetOf(), matches)
            kept != keys && dataCache.saveIgnoredCourses(kept, generation)
        }
        if (changed) effects.changed()
    }

    private suspend fun send(change: PendingCourseIgnore, generation: Int) = sendMutex.withLock {
        if (!sends()) return@withLock
        val moodleId = change.moodleId ?: return@withLock
        // Gone from the file: confirmed already, replaced by a later change,
        // or wiped with the account that made it. Sending it would undo
        // whatever replaced it.
        val current = mutex.withLock {
            generation == currentGeneration() && change in dataCache.loadPendingCourseIgnores()
        }
        if (!current) return@withLock
        val stored = try {
            sender.send(moodleId, change.ignore, change.moodleCourseId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "course ignore PATCH failed: ${change.key}", e)
            false
        }
        if (stored) settle(change, generation)
    }

    private suspend fun settle(change: PendingCourseIgnore, generation: Int) = mutex.withLock {
        val pending = dataCache.loadPendingCourseIgnores()
        if (change !in pending) return@withLock
        if (dataCache.savePendingCourseIgnores(pending - change, generation)) {
            change.key?.let { touchedAt[it] = clock() }
        }
    }

    /**
     * A course the backend ignores but cannot match to the assignments it
     * fetches itself, for want of the numeric Moodle id, gets the id from
     * this device when it knows it.
     */
    private suspend fun fillInMoodleCourseIds(overrides: List<CourseOverrideResult>, generation: Int) {
        val missing = overrides.filter { it.ignoreAssignments == true && it.moodleNumericCourseId == null }
        if (missing.isEmpty()) return
        val known = dataCache.loadMoodleCourseIds()
            .mapKeys { MoodleCourseIds.normalizedIdnumber(it.key) }
        for (o in missing) {
            val semester = o.semester ?: continue
            val courseNo = o.courseNo ?: continue
            val numericId = dataCache.loadCourses(semester)
                .find { it.courseNo == courseNo }
                ?.moodleNumericCourseId
                ?: known[MoodleCourseIds.normalizedIdnumber(o.moodleCourseId)]
                ?: continue
            sendMutex.withLock {
                if (generation != currentGeneration()) return
                try {
                    sender.send(o.moodleCourseId, null, numericId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "moodle_course_id fill-in failed: $semester:$courseNo", e)
                }
            }
        }
    }

    private suspend fun changeFor(
        key: String,
        ignore: Boolean,
        serverRow: CourseOverrideResult?,
        now: Long,
    ): PendingCourseIgnore {
        val semester = CourseIgnores.semesterOf(key)
        val courseNo = key.substringAfter(':')
        val course = dataCache.loadCourses(semester).find { it.courseNo == courseNo }
        val moodleId = serverRow?.moodleCourseId
            ?: course?.moodleIdNumber?.takeIf { it.isNotEmpty() }
            ?: "$semester$courseNo"
        return PendingCourseIgnore(
            semester = semester,
            courseNo = courseNo,
            moodleId = moodleId,
            moodleCourseId = serverRow?.moodleNumericCourseId ?: course?.moodleNumericCourseId,
            ignore = ignore,
            changedAtMs = now,
        )
    }

    private fun List<PendingCourseIgnore>.with(change: PendingCourseIgnore) =
        filterNot { it.key == change.key } + change

    private companion object {
        const val TAG = "CourseIgnoreSync"

        /** The re-enable conflict category course ignores are part of. */
        const val REENABLE_CATEGORY = "assignments"
    }
}
