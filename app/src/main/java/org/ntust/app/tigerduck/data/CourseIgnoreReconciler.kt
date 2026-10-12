package org.ntust.app.tigerduck.data

import org.ntust.app.tigerduck.push.CourseOverrideResult
import org.ntust.app.tigerduck.push.ServerCourse

/**
 * What a full sync does to this device's ignored courses ([CourseIgnores]).
 * Pure, so the rules that decide whether a tap survives a sync are tested
 * without a network or a cache; [CourseIgnoreSync] does the I/O.
 */
object CourseIgnoreReconciler {

    /**
     * What a full sync says: the terms it has course rows for, and the
     * courses ignored in them.
     */
    data class ServerIgnores(val semesters: Set<String>, val keys: Set<String>)

    /**
     * Null when no override carries `ignore_assignments`: an older backend,
     * whose silence says nothing about what is ignored. An account with no
     * override rows at all reads the same way, so it never clears anything.
     */
    fun serverIgnores(
        overrides: List<CourseOverrideResult>,
        serverCourses: List<ServerCourse>,
    ): ServerIgnores? {
        if (overrides.none { it.ignoreAssignments != null }) return null
        val semesters = serverCourses.mapNotNullTo(mutableSetOf()) { it.semester.ifBlank { null } }
        val keys = overrides.asSequence()
            .filter { it.ignoreAssignments == true }
            .mapNotNull { o ->
                val semester = o.semester?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val courseNo = o.courseNo?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                CourseIgnores.key(semester, courseNo)
            }
            .filterTo(mutableSetOf()) { CourseIgnores.semesterOf(it) in semesters }
        return ServerIgnores(semesters, keys)
    }

    /**
     * This device's set after a full sync, or null to leave it as it is.
     *
     * Null when [applies] is false, which is assignment sync being off or a
     * re-enable conflict still waiting for the user, and when [server] is
     * null. Otherwise, in every term the snapshot has course rows for, the
     * server decides, except for [protectedKeys]: changes the backend has not
     * confirmed, or made after the snapshot was fetched, which keep this
     * device's value. Terms with no rows are left alone, since the backend
     * has nothing to say about them.
     */
    fun afterSync(
        local: Set<String>,
        server: ServerIgnores?,
        protectedKeys: Set<String>,
        applies: Boolean,
    ): Set<String>? {
        if (!applies || server == null) return null
        val kept = local.filter {
            CourseIgnores.semesterOf(it) !in server.semesters || it in protectedKeys
        }
        val adopted = server.keys.filter { it !in protectedKeys }
        return (kept + adopted).toSet()
    }

    /**
     * Whether [local] matches [server] in the terms the snapshot covers. A
     * null [server] cannot disagree.
     */
    fun agrees(local: Set<String>, server: ServerIgnores?): Boolean {
        if (server == null) return true
        return local.filterTo(mutableSetOf()) { CourseIgnores.semesterOf(it) in server.semesters } ==
            server.keys
    }
}
