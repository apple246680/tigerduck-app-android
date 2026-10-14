package org.ntust.app.tigerduck.push

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The `course_overrides` of `GET /sync/full`, from an older backend and a current one. */
class CourseOverrideParseTest {

    @Test
    fun `an override from a backend without the field reads as unknown`() {
        val json = JSONObject(
            """{"course_overrides":[{"moodle_id":"1141CS1001301","course_no":"CS1001301","color_hex":"#FF0000"}]}"""
        )

        val override = parseCourseOverrides(json, emptyMap()).single()

        assertNull(override.ignoreAssignments)
        assertNull(override.semester)
        assertNull(override.moodleNumericCourseId)
        assertEquals("#FF0000", override.colorHex)
    }

    @Test
    fun `the ignore setting, its term and the numeric Moodle id are read`() {
        val json = JSONObject(
            """
            {"course_overrides":[
              {"moodle_id":"1141CS1001301","course_no":"CS1001301","semester":"1141",
               "ignore_assignments":true,"ignore_assignments_updated_at":"2026-10-13T00:00:00Z",
               "moodle_course_id":4321},
              {"moodle_id":"1141CS1001302","course_no":"CS1001302","semester":"1141",
               "ignore_assignments":false,"ignore_assignments_updated_at":null,
               "moodle_course_id":null}
            ]}
            """.trimIndent()
        )

        val (ignored, kept) = parseCourseOverrides(json, emptyMap())

        assertEquals(true, ignored.ignoreAssignments)
        assertEquals("2026-10-13T00:00:00Z", ignored.ignoreAssignmentsUpdatedAt)
        assertEquals("1141", ignored.semester)
        assertEquals(4321, ignored.moodleNumericCourseId)
        assertEquals(false, kept.ignoreAssignments)
        assertNull(kept.ignoreAssignmentsUpdatedAt)
        assertNull(kept.moodleNumericCourseId)
    }

    @Test
    fun `the time of the colour pick is read, and is null when the backend leaves it out`() {
        val json = JSONObject(
            """
            {"course_overrides":[
              {"moodle_id":"1141CS1001301","course_no":"CS1001301","color_hex":"#FF0000",
               "color_hex_updated_at":"2026-10-10T16:42:49.666640+00:00"},
              {"moodle_id":"1141CS1001302","course_no":"CS1001302","color_hex":"#00FF00",
               "color_hex_updated_at":null},
              {"moodle_id":"1141CS1001303","course_no":"CS1001303","color_hex":"#0000FF"}
            ]}
            """.trimIndent()
        )

        val (picked, uploaded, older) = parseCourseOverrides(json, emptyMap())

        assertEquals("2026-10-10T16:42:49.666640+00:00", picked.colorHexUpdatedAt)
        assertNull(uploaded.colorHexUpdatedAt)
        assertNull(older.colorHexUpdatedAt)
    }
}
