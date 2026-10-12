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

    /** [setAt] is when a device set the course's setting: null on a row made for a colour. */
    private fun override(
        semester: String?,
        courseNo: String?,
        ignore: Boolean?,
        setAt: String? = ignore?.let { "2026-10-13T00:00:00Z" },
    ) = CourseOverrideResult(
        moodleCourseId = "$semester$courseNo",
        courseNo = courseNo,
        colorHex = null,
        semester = semester,
        ignoreAssignments = ignore,
        ignoreAssignmentsUpdatedAt = setAt,
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
            setOf("1141:AAA", "1141:DDD"), server, emptySet(), applies = true,
        )

        assertEquals(setOf("1141:BBB"), after)
    }

    @Test
    fun `a listed course the backend holds no setting for keeps this device's and is sent again`() {
        // CCC has no override; BBB's was made for a colour.
        val server = CourseIgnoreReconciler.serverIgnores(
            listOf(override("1141", "AAA", ignore = false), override("1141", "BBB", ignore = false, setAt = null)),
            rows,
        )!!
        val local = setOf("1141:AAA", "1141:BBB", "1141:CCC")

        assertEquals(setOf("1141:BBB", "1141:CCC"), CourseIgnoreReconciler.toResend(local, server, emptySet()))
        assertEquals(setOf("1141:CCC"), CourseIgnoreReconciler.toResend(local, server, setOf("1141:BBB")))
        assertEquals(
            setOf("1141:BBB", "1141:CCC"),
            CourseIgnoreReconciler.afterSync(local, server, emptySet(), applies = true),
        )
    }

    @Test
    fun `no overrides at all, as after the backend dropped them with the course rows, is no setting`() {
        val server = CourseIgnoreReconciler.serverIgnores(emptyList(), rows)!!

        assertEquals(setOf("1141:AAA"), CourseIgnoreReconciler.toResend(setOf("1141:AAA"), server, emptySet()))
        assertEquals(
            setOf("1141:AAA"),
            CourseIgnoreReconciler.afterSync(setOf("1141:AAA"), server, emptySet(), applies = true),
        )
    }

    @Test
    fun `taking the backend's set drops what it holds no setting for`() {
        val server = CourseIgnoreReconciler.serverIgnores(
            listOf(override("1141", "BBB", ignore = true)),
            rows,
        )

        val adopted = CourseIgnoreReconciler.adopt(setOf("1141:AAA", "1132:OLD"), server)

        assertEquals(setOf("1132:OLD", "1141:BBB"), adopted)
        assertNull(CourseIgnoreReconciler.adopt(setOf("1141:AAA"), null))
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
        // BBB the backend holds no setting for, so a sync keeps it and sends it again.
        assertTrue(CourseIgnoreReconciler.agrees(setOf("1141:AAA", "1141:BBB"), server))
    }
}
