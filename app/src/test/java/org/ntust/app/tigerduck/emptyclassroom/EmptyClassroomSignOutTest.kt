package org.ntust.app.tigerduck.emptyclassroom

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.emptyclassroom.ClassroomPageFixtures as Pages
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

/** [forgetEmptyClassroom], which `AuthService.logout()` runs. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class EmptyClassroomSignOutTest {

    @Test
    fun `signing out forgets the days read and the building picked`() = runTest {
        val prefs = AppPreferences(RuntimeEnvironment.getApplication()).apply { emptyClassroomBuilding = "TR" }
        val transport = FakeCour01Transport()
        val service = ClassroomUsageService(transport, { _, _ -> true }, {}, { null }, { 0L }, Dispatchers.Unconfined)
        val wednesday = LocalDate.of(2026, 10, 14)
        transport.serve(Pages.page(rows = null, campus = ""))
        transport.serve(Pages.page(viewState = "campus"))
        transport.serve(Pages.page(rows = listOf(Pages.row("IB-508", ".............."))))
        service.usage(wednesday, false, false, ClassroomUsageService.Credentials("B00000000", "not-a-password"))
        assertNotNull(service.keptUsage(wednesday))
        assertEquals("TR", prefs.emptyClassroomBuilding)

        forgetEmptyClassroom(service, prefs)

        assertNull(service.keptUsage(wednesday))
        assertNull(prefs.emptyClassroomBuilding)
    }
}
