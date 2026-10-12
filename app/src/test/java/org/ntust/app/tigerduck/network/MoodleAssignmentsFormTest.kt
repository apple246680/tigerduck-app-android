package org.ntust.app.tigerduck.network

import org.junit.Assert.assertEquals
import org.junit.Test

class MoodleAssignmentsFormTest {

    @Test
    fun `asks only for the courses the user can submit to`() {
        val form = MoodleService.assignmentsForm(listOf(7, 9))

        assertEquals(
            listOf(
                "courseids[0]" to "7",
                "courseids[1]" to "9",
                "capabilities[0]" to "mod/assign:submit",
            ),
            (0 until form.size).map { form.name(it) to form.value(it) },
        )
    }
}
