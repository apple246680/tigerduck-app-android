package org.ntust.app.tigerduck.ui.screen.emptyclassroom

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.ntust.app.tigerduck.academic.AcademicCalendar
import org.ntust.app.tigerduck.academic.Holiday
import org.ntust.app.tigerduck.academic.SemesterTerm
import org.ntust.app.tigerduck.emptyclassroom.ClassroomPageFixtures as Pages
import org.ntust.app.tigerduck.emptyclassroom.ClassroomUsageService
import org.ntust.app.tigerduck.emptyclassroom.FakeCour01Transport
import org.ntust.app.tigerduck.mail.MainDispatcherRule
import org.ntust.app.tigerduck.shared.Course
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The finder's screen state against made-up pages: where it opens, the days it never asks the
 * site about, and what a read leaves on screen.
 */
class EmptyClassroomViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private class FakeEnvironment(var now: LocalDateTime) : FinderEnvironment {
        var calendar = AcademicCalendar(
            revision = 1,
            terms = listOf(SemesterTerm("1151", LocalDate.of(2026, 9, 14), LocalDate.of(2027, 1, 15))),
            holidays = listOf(Holiday(1, "國慶日", "National Day", LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 15))),
        )
        var classTable: List<Course> = emptyList()
        var current = true
        var offline = false
        override var pickedBuilding: String? = null
        override val signedIn = MutableStateFlow(true)
        var account: String? = "B00000000"

        /** Holds the next course read, which then returns the class table as it was asked for. */
        var courseReadGate: CompletableDeferred<Unit>? = null

        override fun now() = now
        override fun calendar() = calendar
        override fun account() = account
        override suspend fun courses(): List<Course> {
            val asked = classTable
            courseReadGate?.let { courseReadGate = null; it.await() }
            return asked
        }
        override fun credentials() = ClassroomUsageService.Credentials("B00000000", "not-a-password")
        override fun isCurrent(credentials: ClassroomUsageService.Credentials) = current
        override fun isOffline(error: Throwable) = offline || error is IOException
    }

    private class Harness(now: LocalDateTime) {
        val env = FakeEnvironment(now)
        val transport = FakeCour01Transport()
        var clockMs = 1_000_000L
        val service = ClassroomUsageService(transport, { _, _ -> true }, {}, { null }, { clockMs }, Dispatchers.Unconfined)
        val model = EmptyClassroomViewModel(service, env)

        fun serve(html: String?, gate: CompletableDeferred<Unit>? = null) = transport.serve(html, gate)

        /** A cold read of a day that is not today: the query page, the campus postback and the date's one page. */
        fun serveDay(rows: List<String>, gate: CompletableDeferred<Unit>? = null) {
            serve(Pages.page(rows = null, campus = ""))
            serve(Pages.page(viewState = "campus"))
            serve(Pages.page(rows = rows), gate)
        }

        val state get() = model.state.value
        val requests get() = transport.sent.size
        val rooms get() = state.usage?.rooms?.map { it.room }
    }

    private fun time(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, day, hour, minute)
    private fun day(day: Int) = LocalDate.of(2026, 10, day)

    @Test
    fun `it opens on the period in progress, and after the last one on the next teaching day`() {
        val wednesday = Harness(time(14, 10, 5))
        wednesday.serveDay(listOf(Pages.row("IB-508", "..............")))
        wednesday.model.appear()
        assertEquals(day(14), wednesday.state.selectedDay)
        assertEquals(2, wednesday.state.selectedPeriod)

        val friday = Harness(time(16, 23))
        friday.serveDay(listOf(Pages.row("IB-508", "..............")))
        friday.model.appear()
        assertEquals(day(19), friday.state.selectedDay)
        assertEquals(0, friday.state.selectedPeriod)
    }

    @Test
    fun `a weekend shows no data without asking the site, and offers the next teaching day`() {
        val harness = Harness(time(14, 10, 5))
        harness.model.select(day(17))

        assertEquals(FinderContent.NoData, harness.state.content)
        assertEquals(0, harness.requests)
        assertEquals(day(19), harness.state.nextTeachingDay)
    }

    @Test
    fun `a holiday shows its name and no rooms, without asking the site`() {
        val harness = Harness(time(14, 22, 30))
        harness.serveDay(listOf(Pages.row("IB-508", "..............")))
        harness.model.appear()
        assertEquals(day(16), harness.state.selectedDay)

        harness.model.select(day(15))

        assertEquals("National Day", (harness.state.content as FinderContent.HolidayOff).holiday.nameEn)
        assertEquals(3, harness.requests)
    }

    @Test
    fun `a day outside the term shows no data, without asking the site`() {
        val harness = Harness(time(14, 10, 5))
        harness.env.calendar = harness.env.calendar.copy(
            terms = listOf(SemesterTerm("1151", LocalDate.of(2026, 9, 14), LocalDate.of(2026, 10, 16))),
        )
        harness.model.select(day(20))

        assertEquals(FinderContent.NoData, harness.state.content)
        assertEquals(0, harness.requests)
        assertNull(harness.state.nextTeachingDay)
    }

    @Test
    fun `the day's rooms show once read, and a day the site has no rooms for is no data`() {
        val harness = Harness(time(14, 22, 30))
        harness.serveDay(listOf(Pages.row("TR-210", "cc..cc..cc...."), Pages.row("IB-508", "..............")))
        harness.model.appear()
        assertEquals(listOf("TR-210", "IB-508"), harness.rooms)

        harness.serveDay(emptyList())
        harness.model.select(day(19))
        assertEquals(FinderContent.NoData, harness.state.content)
        assertEquals(6, harness.requests)
    }

    @Test
    fun `a failed read shows the error, and a later pull that fails keeps the rooms on screen`() {
        val harness = Harness(time(14, 22, 30))
        repeat(4) { harness.serve(Pages.ERROR_PAGE) }
        harness.model.appear()
        assertEquals(FinderContent.Failed(offline = false), harness.state.content)

        harness.serveDay(listOf(Pages.row("IB-508", "..............")))
        harness.model.retry()
        assertEquals(listOf("IB-508"), harness.rooms)
        val fetchedAt = (harness.state.content as FinderContent.Rooms).fetchedAtMs

        harness.clockMs += 60 * 60_000L
        repeat(4) { harness.serve(Pages.ERROR_PAGE) }
        harness.model.refresh()

        val shown = harness.state.content as FinderContent.Rooms
        assertEquals(listOf("IB-508"), harness.rooms)
        assertEquals(fetchedAt, shown.fetchedAtMs)
        assertFalse(harness.state.isRefreshing)
    }

    @Test
    fun `an answer that arrives after the account changed is not shown`() {
        val harness = Harness(time(14, 22, 30))
        harness.env.current = false
        harness.serveDay(listOf(Pages.row("IB-508", "..............")))

        harness.model.appear()

        assertEquals(FinderContent.Loading, harness.state.content)
    }

    @Test
    fun `the user's classes follow the class table while the screen is open`() {
        val harness = Harness(time(14, 10, 5))
        harness.serveDay(listOf(Pages.row("IB-508", "..............")))
        harness.model.appear()
        assertTrue(harness.state.classes.isEmpty())

        harness.env.classTable = listOf(
            Course.fromSchedule(
                courseNo = "CS1001301",
                courseName = "計算機網路",
                schedule = mapOf(3 to listOf("1", "2")),
                classroomMap = mapOf("3-1" to "TR-214", "3-2" to "TR-214"),
            ),
        )
        harness.model.reloadCourses()

        assertEquals(listOf("TR-214"), harness.state.classes.map { it.room })
        assertEquals(listOf(1), harness.state.classes.map { it.lastPeriod })
    }

    @Test
    fun `an account change while the screen is away reads nothing until it comes back`() {
        val harness = Harness(time(14, 22, 30))
        harness.env.pickedBuilding = "IB"

        // A sign-out and a sign-in while the finder is not on screen.
        harness.env.signedIn.value = false
        harness.env.pickedBuilding = null
        harness.env.signedIn.value = true

        assertEquals(0, harness.requests)
        assertEquals(FinderContent.Loading, harness.state.content)
        assertNull(harness.state.pickedBuilding)

        harness.serveDay(listOf(Pages.row("IB-508", "..............")))
        harness.model.appear()
        assertEquals(listOf("IB-508"), harness.rooms)

        harness.model.disappear()
        harness.env.signedIn.value = false
        assertEquals(3, harness.requests)
    }

    private fun networking(room: String) = Course.fromSchedule(
        courseNo = "CS1001301",
        courseName = "計算機網路",
        schedule = mapOf(3 to listOf("1", "2")),
        classroomMap = mapOf("3-1" to room, "3-2" to room),
    )

    @Test
    fun `an account change drops the classes at once`() {
        val harness = Harness(time(14, 10, 5))
        harness.serveDay(listOf(Pages.row("IB-508", "..............")))
        harness.env.classTable = listOf(networking("TR-214"))
        harness.model.appear()
        assertEquals(listOf("TR-214"), harness.state.classes.map { it.room })

        // The new account's course read is held up; the old classes must not wait for it.
        harness.env.courseReadGate = CompletableDeferred()
        harness.env.account = null
        harness.env.signedIn.value = false

        assertTrue(harness.state.classes.isEmpty())
    }

    @Test
    fun `a course read started for the account before shows nothing after the account changes`() {
        val harness = Harness(time(14, 10, 5))
        harness.serveDay(listOf(Pages.row("IB-508", "..............")))
        harness.env.classTable = listOf(networking("TR-214"))
        harness.model.appear()

        // A read of the departing account's courses, which the cache read holds up.
        val departing = CompletableDeferred<Unit>()
        harness.env.courseReadGate = departing
        harness.model.reloadCourses()
        harness.model.disappear()

        // The account signs out and another signs in while the finder is away.
        harness.env.account = null
        harness.env.signedIn.value = false
        harness.env.account = "B11111111"
        harness.env.classTable = listOf(networking("IB-305"))
        harness.env.signedIn.value = true
        departing.complete(Unit)

        assertTrue(harness.state.classes.isEmpty())
        harness.serveDay(listOf(Pages.row("IB-508", "..............")))
        harness.model.appear()
        assertEquals(listOf("IB-305"), harness.state.classes.map { it.room })
    }

    @Test
    fun `a picked building is kept for the next visit, and an account change forgets it`() {
        val harness = Harness(time(14, 10, 5))
        harness.model.pick("IB")
        assertEquals("IB", harness.state.pickedBuilding)
        assertEquals("IB", harness.env.pickedBuilding)

        // A sign-out clears the stored pick, as AuthService.logout() does, then the screen hears.
        harness.env.pickedBuilding = null
        harness.model.accountChanged()
        assertNull(harness.state.pickedBuilding)
    }

    @Test
    fun `leaving the screen stops its read, so nothing it started lands later`() {
        val harness = Harness(time(14, 22, 30))
        val gate = CompletableDeferred<Unit>()
        harness.serveDay(listOf(Pages.row("IB-508", "..............")), gate)
        harness.model.appear()
        assertEquals(3, harness.requests)

        harness.model.disappear()
        gate.complete(Unit)

        assertEquals(FinderContent.Loading, harness.state.content)
        assertNull(harness.service.keptUsage(day(16)))
    }

    @Test
    fun `a second appear while the day loads keeps that load`() {
        val harness = Harness(time(14, 22, 30))
        val gate = CompletableDeferred<Unit>()
        harness.serveDay(listOf(Pages.row("IB-508", "..............")), gate)
        harness.model.appear()

        harness.model.appear()
        gate.complete(Unit)

        assertEquals(listOf("IB-508"), harness.rooms)
        assertEquals(3, harness.requests)
    }

    @Test
    fun `a day chosen while another loads shows its own rooms`() {
        val harness = Harness(time(14, 22, 30))
        val first = CompletableDeferred<Unit>()
        harness.serveDay(listOf(Pages.row("IB-508", "..............")), first)
        harness.model.appear()
        assertEquals(day(16), harness.state.selectedDay)

        // Choosing another day cancels the first read, so the next starts from a new campus page.
        harness.serveDay(listOf(Pages.row("TR-209", "..............")))
        harness.model.select(day(19))
        first.complete(Unit)

        assertEquals(listOf("TR-209"), harness.rooms)
        assertEquals(day(19), harness.state.selectedDay)
    }

    @Test
    fun `a day read before shows at once while it is read again`() {
        val harness = Harness(time(14, 22, 30))
        harness.serveDay(listOf(Pages.row("IB-508", "..............")))
        harness.model.appear()
        harness.serveDay(listOf(Pages.row("TR-209", "..............")))
        harness.model.select(day(19))
        harness.clockMs += 4 * 60 * 60_000L

        val gate = CompletableDeferred<Unit>()
        harness.serveDay(listOf(Pages.row("IB-401", "..............")), gate)
        harness.model.select(day(16))

        assertEquals(listOf("IB-508"), harness.rooms)
        assertTrue(harness.state.isReloading)
        gate.complete(Unit)
        assertEquals(listOf("IB-401"), harness.rooms)
        assertFalse(harness.state.isReloading)
    }

    @Test
    fun `today names the periods that have ended and the one in progress`() {
        val harness = Harness(time(14, 10, 5))
        harness.serveDay(listOf(Pages.row("IB-508", "..............")))
        harness.model.appear()
        assertEquals(2, harness.state.endedPeriods)
        assertEquals(2, harness.state.currentPeriod)
        assertEquals(14, harness.state.days.size)

        harness.serveDay(listOf(Pages.row("IB-508", "..............")))
        harness.model.select(day(16))
        assertEquals(0, harness.state.endedPeriods)
        assertNull(harness.state.currentPeriod)
    }
}
