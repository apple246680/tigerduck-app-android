package org.ntust.app.tigerduck.data.sharing

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.network.NetworkChecker
import org.ntust.app.tigerduck.network.model.CourseSearchResult
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.logSummary
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The courses of a class table imported from a share code, which carries course codes only,
 * looked up in NTUST's course query as the user's own are.
 */
@Singleton
class ImportedCourseLookup internal constructor(
    /** QueryCourse's rows for one course code, past the cache when `fresh`; throws when the query fails. */
    private val query: suspend (semester: String, courseNo: String, fresh: Boolean) -> List<CourseSearchResult>,
    /** One course from the rows the query returned for it. */
    private val courseFrom: (List<CourseSearchResult>) -> Course,
    private val isOnline: () -> Boolean,
) {
    @Inject
    constructor(courseService: CourseService, networkChecker: NetworkChecker) : this(
        query = { semester, courseNo, fresh -> courseService.lookupCourse(semester, courseNo, fresh = fresh) },
        courseFrom = { courseService.courseFrom(it, moodle = null) },
        isOnline = networkChecker::isAvailable,
    )

    /**
     * Requests to the school's course query at once, shared by imports and refreshes, as the iOS
     * app's `maxConcurrentRequests`: a share code may carry hundreds of course codes.
     */
    private val requests = Semaphore(MAX_CONCURRENT_REQUESTS)

    private suspend fun ask(semester: String, courseNo: String, fresh: Boolean): List<CourseSearchResult> =
        requests.withPermit { query(semester, courseNo, fresh) }

    /**
     * For an import: each course the query knows, in the code's order, and none it does not
     * know. Throws when a query fails, so an import never keeps half a class table.
     */
    suspend fun courses(semester: String, courseNos: List<String>): List<Course> = coroutineScope {
        courseNos.map { courseNo ->
            async {
                ask(semester, courseNo, false).takeIf { it.isNotEmpty() }?.let(courseFrom)
            }
        }.awaitAll().filterNotNull()
    }

    /**
     * For a pull to refresh on someone else's class table: the term looked up again, past the
     * cache. A course the query does not answer keeps its row in [current]. Null when no lookup
     * went through, offline included, so the page can say the refresh failed.
     */
    suspend fun refreshed(semester: String, courseNos: List<String>, current: List<Course>): List<Course>? {
        if (!isOnline()) return null
        val currentByNo = current.associateBy { it.courseNo }
        val answers = coroutineScope {
            courseNos.map { courseNo ->
                async {
                    try {
                        val results = ask(semester, courseNo, true)
                        Answer(if (results.isEmpty()) currentByNo[courseNo] else courseFrom(results), failed = false)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not look up an imported course: ${e.logSummary()}")
                        Answer(currentByNo[courseNo], failed = true)
                    }
                }
            }.awaitAll()
        }
        if (answers.all { it.failed }) return null
        return answers.mapNotNull { it.course }
    }

    private class Answer(val course: Course?, val failed: Boolean)

    private companion object {
        const val TAG = "ImportedCourseLookup"
        const val MAX_CONCURRENT_REQUESTS = 4
    }
}
