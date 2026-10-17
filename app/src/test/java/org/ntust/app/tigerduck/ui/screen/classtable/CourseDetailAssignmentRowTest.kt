package org.ntust.app.tigerduck.ui.screen.classtable

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.model.Assignment
import org.ntust.app.tigerduck.shared.Course
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Date

/**
 * The course detail's assignment rows: a tap opens the page Moodle gave, so
 * a row without one is not a button (tigerduck-app-android#313).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CourseDetailAssignmentRowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun assignment(moodleUrl: String?) = Assignment(
        assignmentId = "4567",
        courseNo = "CS1001301",
        courseName = "Calculus",
        title = "Homework 3",
        dueDate = Date(System.currentTimeMillis() + 86_400_000L),
        moodleUrl = moodleUrl,
    )

    private fun show(assignment: Assignment) {
        composeRule.setContent {
            Column {
                CourseDetailContent(
                    course = Course(courseNo = "CS1001301", courseName = "Calculus"),
                    title = "Calculus",
                    classroom = "TR-313",
                    timeRange = null,
                    assignments = listOf(assignment),
                    onRename = null,
                    onOpenInMoodle = null,
                )
            }
        }
    }

    @Test
    fun `an assignment with a Moodle page is a button`() {
        show(assignment("https://moodle2.ntust.edu.tw/mod/assign/view.php?id=890"))

        composeRule.onNodeWithText("Homework 3").assertHasClickAction()
    }

    @Test
    fun `one Moodle gave no address is not`() {
        show(assignment(moodleUrl = null))

        composeRule.onNodeWithText("Homework 3").assertHasNoClickAction()
    }
}
