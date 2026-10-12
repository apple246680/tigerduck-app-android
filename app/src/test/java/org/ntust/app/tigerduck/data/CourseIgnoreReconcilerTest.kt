package org.ntust.app.tigerduck.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.push.CourseOverrideResult
import org.ntust.app.tigerduck.push.ServerCourse

class CourseIgnoreReconcilerTest {

    private fun row(semester: String, courseNo: String) =
        ServerCourse(courseNo = courseNo, courseName = courseNo, semester = semester)

    private fun override(
        semester: String?,
        courseNo: String?,
        ignore: Boolean?,
    ) = CourseOverrideResult(
        moodleCourseId = "$semester$courseNo",
        courseNo = courseNo,
        colorHex = null,
        semester = semester,
        ignoreAssignments = ignore,
    )

    private val rows = listOf(row("1141", "AAA"), row("1141", "BBB"), row("1141", "CCC"))

    @Test
    fun `an older backend, which sends no ignore field, leaves the set alone`() {
        val server = CourseIgnoreReconciler.serverIgnores(
            listOf(override("1141", "AAA", ignore = null)),
            rows,
        )

        assertNull(server)
        assertNull(CourseIgnoreReconciler.afterSync(setOf("1141:AAA"), server, emptySet(), applies = true))
    }

    @Test
    fun `with assignment sync off or a re-enable conflict open nothing is applied`() {
        val server = CourseIgnoreReconciler.serverIgnores(
            listOf(override("1141", "BBB", ignore = true)),
            rows,
        )

        assertNull(CourseIgnoreReconciler.afterSync(setOf("1141:AAA"), server, emptySet(), applies = false))
    }

    @Test
    fun `in a term the snapshot covers the backend decides`() {
        val server = CourseIgnoreReconciler.serverIgnores(
            listOf(override("1141", "AAA", ignore = false), override("1141", "BBB", ignore = true)),
            rows,
        )

        val after = CourseIgnoreReconciler.afterSync(
            setOf("1141:AAA", "1141:CCC"), server, emptySet(), applies = true,
        )

        assertEquals(setOf("1141:BBB"), after)
    }

    @Test
    fun `a change not yet confirmed keeps this device's value either way`() {
        val server = CourseIgnoreReconciler.serverIgnores(
            listOf(override("1141", "AAA", ignore = false), override("1141", "BBB", ignore = true)),
            rows,
        )

        val after = CourseIgnoreReconciler.afterSync(
            local = setOf("1141:AAA"),
            server = server,
            protectedKeys = setOf("1141:AAA", "1141:BBB"),
            applies = true,
        )

        assertEquals(setOf("1141:AAA"), after)
    }

    @Test
    fun `a term without course rows in the snapshot is left alone`() {
        val server = CourseIgnoreReconciler.serverIgnores(
            listOf(override("1141", "AAA", ignore = false)),
            rows,
        )

        val after = CourseIgnoreReconciler.afterSync(
            setOf("1132:OLD", "1141:AAA"), server, emptySet(), applies = true,
        )

        assertEquals(setOf("1132:OLD"), after)
    }

    @Test
    fun `an override with no term or course number names no course`() {
        val server = CourseIgnoreReconciler.serverIgnores(
            listOf(override("", "AAA", ignore = true), override("1141", null, ignore = true)),
            rows,
        )

        assertEquals(emptySet<String>(), server?.keys)
    }

    @Test
    fun `the re-enable check compares only the terms the snapshot covers`() {
        val server = CourseIgnoreReconciler.serverIgnores(
            listOf(override("1141", "AAA", ignore = true)),
            rows,
        )

        assertTrue(CourseIgnoreReconciler.agrees(setOf("1141:AAA", "1132:OLD"), server))
        assertFalse(CourseIgnoreReconciler.agrees(setOf("1141:BBB"), server))
        assertTrue(CourseIgnoreReconciler.agrees(setOf("1141:BBB"), null))
    }
}
