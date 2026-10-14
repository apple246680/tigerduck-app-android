package org.ntust.app.tigerduck.data.sharing

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.ntust.app.tigerduck.network.CourseService
import org.ntust.app.tigerduck.network.NetworkChecker
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.logSummary
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The courses of a class table imported from a share code, which carries course codes only,
 * looked up in NTUST's course query as the user's own are.
 */
@Singleton
class ImportedCourseLookup @Inject constructor(
    private val courseService: CourseService,
    private val networkChecker: NetworkChecker,
) {
    /**
     * For an import: each course the query knows, in the code's order, and none it does not
     * know. Throws when a query fails, so an import never keeps half a class table.
     */
    suspend fun courses(semester: String, courseNos: List<String>): List<Course> = coroutineScope {
        courseNos.map { courseNo ->
            async {
                courseService.lookupCourse(semester, courseNo)
                    .takeIf { it.isNotEmpty() }
                    ?.let { courseService.courseFrom(it, moodle = null) }
            }
        }.awaitAll().filterNotNull()
    }

    /**
     * For a pull to refresh on someone else's class table: the term looked up again, past the
     * cache. A course the query does not answer keeps its row in [current]. Null when no lookup
     * went through, offline included, so the page can say the refresh failed.
     */
    suspend fun refreshed(semester: String, courseNos: List<String>, current: List<Course>): List<Course>? {
        if (!networkChecker.isAvailable()) return null
        val currentByNo = current.associateBy { it.courseNo }
        val answers = coroutineScope {
            courseNos.map { courseNo ->
                async {
                    try {
                        val results = courseService.lookupCourse(semester, courseNo, fresh = true)
                        val course = if (results.isEmpty()) currentByNo[courseNo] else courseService.courseFrom(results, null)
                        Answer(course, failed = false)
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
    }
}
