package org.ntust.app.tigerduck.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.academic.Holiday
import org.ntust.app.tigerduck.shared.WearProtocol
import java.time.LocalDate

class WearQuietDaysTest {

    private fun date(text: String) = LocalDate.parse(text)

    private val today = date("2026-10-05")

    private fun calendarWith(vararg holidays: Holiday) =
        AcademicCalendar(revision = 1L, terms = emptyList(), holidays = holidays.toList())

    @Test
    fun `the watch reads back the quiet days the phone sends`() {
        val calendar = calendarWith(
            Holiday(1, "National Day", "National Day", date("2026-10-09"), date("2026-10-10")),
            Holiday(2, "Anniversary", "Anniversary", date("2026-11-14"), date("2026-11-14")),
        )
        val sent = wearQuietDays(calendar, optedInHolidayIds = setOf(2), today = today)
        assertEquals(setOf(date("2026-10-09"), date("2026-10-10")), WearProtocol.Schedule.decodeQuietDays(sent))
    }

    @Test
    fun `a holiday that is over is not sent`() {
        val calendar = calendarWith(
            Holiday(1, "Mid-Autumn", "Mid-Autumn", date("2026-09-25"), date("2026-09-27")),
        )
        assertTrue(wearQuietDays(calendar, emptySet(), today).isEmpty())
    }

    @Test
    fun `a holiday running past the horizon is cut at it`() {
        val calendar = calendarWith(
            Holiday(1, "Typo", "Typo", date("2026-10-01"), date("2036-10-01")),
        )
        val sent = wearQuietDays(calendar, emptySet(), today)
        assertEquals(WEAR_QUIET_DAYS_AHEAD.toInt() + 1, sent.size)
        assertEquals(today.toString(), sent.first())
        assertEquals(today.plusDays(WEAR_QUIET_DAYS_AHEAD).toString(), sent.last())
    }

    @Test
    fun `a phone that has no calendar yet sends no quiet days`() {
        assertTrue(wearQuietDays(AcademicCalendar.EMPTY, emptySet(), today).isEmpty())
    }
}
