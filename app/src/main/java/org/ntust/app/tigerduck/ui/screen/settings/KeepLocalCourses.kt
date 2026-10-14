package org.ntust.app.tigerduck.ui.screen.settings

import android.util.Log
import kotlinx.coroutines.CancellationException
import org.ntust.app.tigerduck.data.CourseTombstoneKeys
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.logSummary

/** The course requests Use Local sends, so a test can stand in for the backend. */
internal interface CourseSyncBackend {
    /** `DELETE /sync/courses?semester=`: a reset of one term, never of every term. */
    suspend fun deleteAllCourses(semester: String)
    suspend fun uploadCourses(courses: List<Course>, semester: String)
    suspend fun deleteCourse(courseKey: String)
}

internal class PushCourseSyncBackend(private val api: PushApiClient) : CourseSyncBackend {
    override suspend fun deleteAllCourses(semester: String) = api.deleteAllCourses(semester)

    override suspend fun uploadCourses(courses: List<Course>, semester: String) {
        api.uploadCourses(courses, semester)
    }

    override suspend fun deleteCourse(courseKey: String) = api.deleteCourse(courseKey)
}

/**
 * Use Local for courses, after course sync is switched back on, term by term
 * (tigerduck-app/tigerduck-app#235 on iOS, tigerduck-app-android#153):
 *
 * 1. Reset a term this phone caches courses for. Every tombstone in it
 *    becomes this phone's reset tombstone.
 * 2. Upload the term's cached courses, hand-typed ones included. The upload
 *    releases those tombstones.
 * 3. Delete every course hidden here in the term. A single-course delete
 *    leaves a tombstone that binds every device, this one included, so the
 *    roster a later refresh uploads cannot bring the course back. The
 *    backend writes it only for a row it has, so a hidden course the cache
 *    lacks goes up in step 2 as a stub named by its course number. A term
 *    with hidden courses and none cached gets steps 2 and 3 without the
 *    reset.
 *
 * [terms] maps each semester to its cached courses; [hiding] is the
 * deleted-course store, where a legacy bare entry hides its course in every
 * term. Not a full reset: that erases every term's tombstones and stamps
 * `courses_reset_at`, which this phone's next sync then took for a reset made
 * elsewhere, dropping its hidden and hand-typed courses.
 *
 * Every request is awaited and a failure is thrown, for the caller to ask
 * again. Once [isCurrent] turns false, as at a sign-out, nothing more is sent
 * and it throws [CancellationException].
 */
internal suspend fun keepLocalCourses(
    terms: List<Pair<String, List<Course>>>,
    hiding: Set<String>,
    backend: CourseSyncBackend,
    isCurrent: () -> Boolean = { true },
) {
    fun ensureCurrent() {
        if (!isCurrent()) throw CancellationException("signed out during Use Local")
    }
    for ((semester, courses) in terms) {
        val hiddenNos = CourseTombstoneKeys.hiddenIn(semester, hiding)
        val cachedNos = courses.mapTo(mutableSetOf()) { it.courseNo }
        val stubs = (hiddenNos - cachedNos).sorted().map { Course(courseNo = it, courseName = it) }
        if (courses.isEmpty() && stubs.isEmpty()) continue
        if (courses.isNotEmpty()) {
            ensureCurrent()
            backend.deleteAllCourses(semester)
        }
        ensureCurrent()
        backend.uploadCourses(courses + stubs, semester)
        for (courseNo in hiddenNos.sorted()) {
            ensureCurrent()
            backend.deleteCourse("client:$semester:$courseNo")
        }
    }
}

/**
 * What Use Local needs around its requests: the reset latch and stamps of
 * `DataCache` (see `DataCache.beginReset`), and a way to ask the question
 * again.
 */
internal interface UseLocalHost {
    /** Latches [semester] as mid-reset; false when a class-table reset already holds it. */
    fun beginReset(semester: String): Boolean
    fun endReset(semester: String)
    suspend fun stampReset(semester: String, atMs: Long)

    /** Marks courses for the re-enable check again. */
    fun askAgain()
}

/**
 * [keepLocalCourses] over every term in [terms], latched as a class-table
 * reset's are and stamped as reset when it ends, so a sync that read a term
 * mid-way does not un-hide the courses the upload puts back before their
 * deletes. A failure asks again ([UseLocalHost.askAgain]): until a term's
 * upload lands, the server holds none of its courses, and the reset's
 * tombstones hide them on every other device. Stopped by a sign-out, it asks
 * and stamps nothing for the next account; the latch goes either way.
 */
internal suspend fun useLocalCourses(
    terms: List<Pair<String, List<Course>>>,
    hiding: Set<String>,
    backend: CourseSyncBackend,
    host: UseLocalHost,
    now: () -> Long,
    isCurrent: () -> Boolean,
) {
    val latched = terms.map { it.first }.filter(host::beginReset)
    try {
        try {
            keepLocalCourses(terms, hiding, backend, isCurrent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("SyncReenable", "[reenable] keep-local courses failed, asking again: ${e.logSummary()}")
            if (isCurrent()) host.askAgain()
        }
        // Before the latch goes, so no sync reads a term between the two.
        val doneAt = now()
        for (semester in latched) host.stampReset(semester, doneAt)
    } finally {
        latched.forEach(host::endReset)
    }
}
