package org.ntust.app.tigerduck.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class QuietDaysTest {

    @Test
    fun `quiet days survive the trip to the watch`() {
        val days = setOf(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 9))
        val wire = QuietDays.encode(days)
        assertEquals(listOf("2026-10-09", "2026-10-10"), wire)
        assertEquals(days, QuietDays.decode(wire))
    }

    @Test
    fun `a payload without quiet days reads as none`() {
        assertTrue(QuietDays.decode(null).isEmpty())
    }

    @Test
    fun `an entry that is not a date is dropped and the rest kept`() {
        assertEquals(
            setOf(LocalDate.of(2026, 10, 9)),
            QuietDays.decode(listOf("2026-10-09", "10/10", "", "2026-02-30")),
        )
    }
}
