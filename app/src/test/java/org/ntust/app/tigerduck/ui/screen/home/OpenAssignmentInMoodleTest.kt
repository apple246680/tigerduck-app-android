package org.ntust.app.tigerduck.ui.screen.home

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.model.Assignment
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** What a tap on an assignment starts: the Moodle app, the browser, or nothing. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OpenAssignmentInMoodleTest {

    private val app: Application = RuntimeEnvironment.getApplication()

    private fun assignment(moodleUrl: String?) = Assignment(
        assignmentId = "4567",
        courseNo = "CS3204301",
        courseName = "Operating Systems",
        title = "Lab 2",
        dueDate = Date(0),
        moodleUrl = moodleUrl,
    )

    private fun started(): Intent? = shadowOf(app).nextStartedActivity

    @Test
    fun `an assignment opens in the Moodle app`() {
        openAssignmentInMoodle(app, assignment("https://moodle2.ntust.edu.tw/mod/assign/view.php?id=890"))

        val intent = started()
        assertEquals(Intent.ACTION_VIEW, intent?.action)
        assertEquals("moodlemobile://https://moodle2.ntust.edu.tw?redirect=/mod/assign/view.php?id=890", intent?.dataString)
        assertNull("one Activity, not the browser behind the app", started())
    }

    @Test
    fun `the browser takes the page when no app takes the link`() {
        // A device without the Moodle app refuses the moodlemobile:// link.
        val context: Context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) {
                if (intent.data?.scheme == "moodlemobile") throw ActivityNotFoundException()
                super.startActivity(intent)
            }
        }

        openAssignmentInMoodle(context, assignment("https://moodle2.ntust.edu.tw/mod/assign/view.php?id=890"))

        assertEquals("https://moodle2.ntust.edu.tw/mod/assign/view.php?id=890", started()?.dataString)
    }

    @Test
    fun `an assignment Moodle gave no address opens nothing`() {
        openAssignmentInMoodle(app, assignment(moodleUrl = null))

        assertNull(started())
    }
}
