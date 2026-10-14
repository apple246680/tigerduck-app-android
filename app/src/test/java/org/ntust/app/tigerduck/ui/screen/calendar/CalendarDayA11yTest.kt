package org.ntust.app.tigerduck.ui.screen.calendar

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.AppConstants
import org.ntust.app.tigerduck.data.model.CalendarEvent
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/** What TalkBack reads for a day of the calendar's month grid. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en-rUS")
class CalendarDayA11yTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun day(iso: String): Date =
        Date.from(LocalDate.parse(iso).atStartOfDay(AppConstants.TAIPEI_ZONE).toInstant())

    @Test
    fun `a day is read as its full date and its number of events`() {
        assertEquals(
            "Wednesday, October 14, 2026, 2 events",
            calendarDayLabel("%1\$@, %2\$lld events", day("2026-10-14"), 2, Locale.US),
        )
    }

    @Test
    fun `the month grid labels each day and says which one is selected`() {
        val events = listOf(
            CalendarEvent("e1", "Midterm", day("2026-10-14"), "exam"),
            CalendarEvent("e2", "Deadline", day("2026-10-14"), "moodle"),
        )
        composeRule.setContent {
            CalendarGrid(
                monthDate = day("2026-10-01"),
                selectedDate = day("2026-10-14"),
                events = events,
                onDateSelected = {},
            )
        }

        composeRule.onNodeWithContentDescription("Wednesday, October 14, 2026, 2 events").assertIsSelected()
        composeRule.onNodeWithContentDescription("Thursday, October 15, 2026, 0 events")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
        // The bare day number is no longer read on its own.
        composeRule.onNodeWithText("15").assertDoesNotExist()
    }
}
