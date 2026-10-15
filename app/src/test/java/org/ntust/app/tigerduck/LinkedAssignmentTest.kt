package org.ntust.app.tigerduck

import android.app.Application
import android.content.Intent
import android.net.Uri
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.model.Assignment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** When MainActivity opens the assignment a `tigerduck://assignment/<id>` link names. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LinkedAssignmentTest {

    private fun linkIntent(link: String = "tigerduck://assignment/900002") =
        Intent(Intent.ACTION_VIEW, Uri.parse(link))

    private fun assignment(id: String) = Assignment(
        assignmentId = id,
        courseNo = "CS3204301",
        courseName = "Operating Systems",
        title = "Lab $id",
        dueDate = Date(0),
    )

    @Test
    fun `a fresh delivery opens the link's assignment, once`() {
        val intent = linkIntent()

        assertEquals("900002", takeLinkedAssignmentId(intent, restored = false))
        assertNull("the link is taken off the intent", intent.data)
        assertNull("so the same intent opens nothing again", takeLinkedAssignmentId(intent, restored = false))
    }

    @Test
    fun `an Activity rebuilt from saved state opens nothing, and drops the link`() {
        val intent = linkIntent()

        assertNull(takeLinkedAssignmentId(intent, restored = true))
        assertNull(intent.data)
    }

    @Test
    fun `a relaunch from Recents opens nothing`() {
        val intent = linkIntent().addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)

        assertNull(takeLinkedAssignmentId(intent, restored = false))
        assertNull(intent.data)
    }

    @Test
    fun `another link is left for the route to open`() {
        val intent = linkIntent("tigerduck://classtable")

        assertNull(takeLinkedAssignmentId(intent, restored = false))
        assertEquals("tigerduck://classtable", intent.dataString)
    }

    @Test
    fun `a malformed assignment link opens nothing`() {
        assertNull(takeLinkedAssignmentId(linkIntent("tigerduck://assignment/abc"), restored = false))
    }

    @Test
    fun `only a cached assignment opens`() {
        val cached = listOf(assignment("900001"), assignment("900002"))

        assertEquals("900002", linkedAssignment("900002", cached)?.assignmentId)
        assertNull(linkedAssignment("999999", cached))
        assertNull(linkedAssignment("900002", emptyList()))
    }
}
