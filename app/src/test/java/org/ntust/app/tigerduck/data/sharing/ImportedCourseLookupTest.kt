package org.ntust.app.tigerduck.data.sharing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.network.model.CourseSearchResult
import org.ntust.app.tigerduck.shared.Course
import java.io.IOException

/**
 * Looking an imported class table up in NTUST's course query: a few requests at a time however
 * many courses a code carries, unknown codes apart from failed requests.
 */
class ImportedCourseLookupTest {

    private var inFlight = 0
    private var mostInFlight = 0
    private val gate = CompletableDeferred<Unit>()
    private val failing = mutableSetOf<String>()

    private fun row(courseNo: String) = CourseSearchResult(
        semester = "1151", courseNo = courseNo, courseName = "Course $courseNo", courseTeacher = "",
        dimension = null, creditPoint = "3", requireOption = null, allYear = null, chooseStudent = null,
        restrict1 = null, restrict2 = null, courseTimes = null, practicalTimes = null, classRoomNo = null,
        node = null, contents = null,
    )

    private val lookup = ImportedCourseLookup(
        query = { _, courseNo, _ ->
            inFlight++
            mostInFlight = maxOf(mostInFlight, inFlight)
            try {
                gate.await()
                if (courseNo in failing) throw IOException("query failed")
                if (courseNo.startsWith("XX")) emptyList() else listOf(row(courseNo))
            } finally {
                inFlight--
            }
        },
        courseFrom = { rows -> Course(courseNo = rows.first().courseNo, courseName = rows.first().courseName) },
        isOnline = { true },
    )

    private val codes = (1..30).map { "CS10%05d".format(it) }

    @Test
    fun `a code of many courses is looked up four at a time`() = runTest {
        var result: List<Course>? = null
        launch { result = lookup.courses("1151", codes) }
        advanceUntilIdle()

        assertEquals(4, mostInFlight)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(codes, result?.map { it.courseNo })
        assertTrue(mostInFlight <= 4)
    }

    @Test
    fun `a refresh is looked up four at a time too`() = runTest {
        launch { lookup.refreshed("1151", codes, emptyList()) }
        advanceUntilIdle()

        assertEquals(4, mostInFlight)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(mostInFlight <= 4)
    }

    @Test
    fun `an unknown code is left out, and a failed request fails the import`() = runTest {
        gate.complete(Unit)

        assertEquals(listOf("CS1000001"), lookup.courses("1151", listOf("CS1000001", "XX1000002")).map { it.courseNo })

        failing += "CS1000001"
        val failure = runCatching { lookup.courses("1151", listOf("CS1000001", "XX1000002")) }.exceptionOrNull()
        assertTrue(failure is IOException)
    }

    @Test
    fun `a refresh keeps the rows of failed and unknown codes, and with every request failed is no answer`() = runTest {
        gate.complete(Unit)
        val kept = listOf(Course(courseNo = "CS1000001", courseName = "Kept"), Course(courseNo = "XX1000002", courseName = "Old"))
        failing += "CS1000001"

        val refreshed = lookup.refreshed("1151", listOf("CS1000001", "XX1000002", "CS1000003"), kept)
        assertEquals(listOf("Kept", "Old", "Course CS1000003"), refreshed?.map { it.courseName })

        failing += listOf("XX1000002", "CS1000003")
        assertNull(lookup.refreshed("1151", listOf("CS1000001", "XX1000002", "CS1000003"), kept))
    }
}
