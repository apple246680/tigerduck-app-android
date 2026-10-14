package org.ntust.app.tigerduck.data.preferences

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What a sync keeps per account goes at sign-out ([AppPreferences.clearSyncStamps]),
 * and the upload ledger also when a sync switch comes back on
 * ([AppPreferences.forgetUploads]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SyncStampsTest {

    private val prefs = AppPreferences(RuntimeEnvironment.getApplication())

    private fun keep() {
        prefs.lastSeenCoursesResetAt = "2026-10-10T16:42:49+00:00"
        prefs.assignmentsUploadFingerprint = "abc"
        prefs.assignmentsUploadedAtMs = 42L
    }

    @Test
    fun `sign-out forgets the course reset read and the upload ledger`() {
        keep()

        prefs.clearSyncStamps()

        assertNull(prefs.lastSeenCoursesResetAt)
        assertNull(prefs.assignmentsUploadFingerprint)
        assertEquals(0L, prefs.assignmentsUploadedAtMs)
    }

    @Test
    fun `a switch coming back on forgets the upload ledger alone`() {
        keep()

        prefs.forgetUploads()

        assertNull(prefs.assignmentsUploadFingerprint)
        assertEquals(0L, prefs.assignmentsUploadedAtMs)
        assertEquals("2026-10-10T16:42:49+00:00", prefs.lastSeenCoursesResetAt)
    }

    @Test
    fun `the older build's sync stamp is read once, and sign-out forgets it too`() {
        val legacy = RuntimeEnvironment.getApplication().getSharedPreferences("tigerduck_sync", 0)
        legacy.edit().putLong("last_course_sync_at", 42L).commit()

        assertEquals(42L, prefs.legacyLastCourseSyncAtMs)
        prefs.forgetLegacyLastCourseSyncAt()
        assertEquals(0L, prefs.legacyLastCourseSyncAtMs)

        legacy.edit().putLong("last_course_sync_at", 42L).commit()
        prefs.clearSyncStamps()
        assertEquals(0L, prefs.legacyLastCourseSyncAtMs)
    }

    @Test
    fun `any sync switch turned back on forgets the upload ledger, wherever it is turned`() {
        // Onboarding's TigerSync step sets the switches directly, not through
        // the Settings screens.
        for (flip in listOf<(Boolean) -> Unit>(
            { prefs.cloudSyncEnabled = it },
            { prefs.syncCourses = it },
            { prefs.syncCourseColors = it },
            { prefs.syncCourseNames = it },
            { prefs.syncAssignments = it },
        )) {
            flip(false)
            keep()
            flip(true)
            assertNull(prefs.assignmentsUploadFingerprint)
        }
    }

    @Test
    fun `a switch set on again while on keeps the ledger`() {
        prefs.syncAssignments = true
        keep()

        prefs.syncAssignments = true

        assertEquals("abc", prefs.assignmentsUploadFingerprint)
    }
}
