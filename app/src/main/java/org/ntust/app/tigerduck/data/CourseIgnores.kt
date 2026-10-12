package org.ntust.app.tigerduck.data

import org.ntust.app.tigerduck.data.model.Assignment

/**
 * Courses whose every assignment the user ignores, held as
 * "<semester>:<courseNo>" keys: the shape of the Apple app's
 * `ignored_courses.json`, and the key the sync derives from a course
 * override.
 *
 * An assignment keeps no record of its own for this. It counts as ignored
 * while its course is, new ones included, and goes back to whatever it was,
 * marked done or ignored on its own, once the course no longer is.
 */
object CourseIgnores {

    fun key(semester: String, courseNo: String): String = "$semester:$courseNo"

    fun semesterOf(key: String): String = key.substringBefore(':')

    /** The course numbers [keys] ignore in [semester]. */
    fun courseNosIn(semester: String, keys: Set<String>): Set<String> {
        val prefix = key(semester, "")
        return keys.asSequence()
            .filter { it.startsWith(prefix) }
            .mapTo(mutableSetOf()) { it.removePrefix(prefix) }
    }

    /**
     * Ids of the assignments in [all] whose course [keys] ignore. Cached
     * assignments all belong to the term in session, which [semester] is.
     */
    fun assignmentIds(all: List<Assignment>, keys: Set<String>, semester: String): Set<String> {
        if (keys.isEmpty()) return emptySet()
        return all.asSequence()
            .filter { key(semester, it.courseNo) in keys }
            .mapTo(mutableSetOf()) { it.assignmentId }
    }
}
