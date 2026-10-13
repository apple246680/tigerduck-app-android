package org.ntust.app.tigerduck.ui.screen.settings

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.ui.screen.home.AssignmentOverrideReconciler
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [markForReenableCheck]: which categories switched back on wait for the
 * re-enable check. A phone that has not synced the signed-in account holds
 * none of its data, so the check has nothing to compare there, and Use Local
 * would send the emptied caches over the server's.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class ReenableMarksTest {

    private lateinit var prefs: AppPreferences

    @Before
    fun setUp() {
        prefs = AppPreferences(RuntimeEnvironment.getApplication())
    }

    // What turning TigerSync on does with every category's switch on.
    private fun turnTigerSyncOn(signedIn: Boolean = true) {
        for (category in CATEGORIES) markForReenableCheck(prefs, category, signedIn)
    }

    @Test
    fun `a phone that has synced the account marks every category switched back on`() {
        prefs.hasSyncedAccount = true

        turnTigerSyncOn()

        assertEquals(CATEGORIES, prefs.pendingConflictCategories)
    }

    @Test
    fun `after a sign-out, the next sign-in marks nothing and its first sync takes the server's marks`() {
        prefs.hasSyncedAccount = true
        // What AuthService.logout clears.
        prefs.clearSyncStamps()

        turnTigerSyncOn()

        assertEquals(emptySet<String>(), prefs.pendingConflictCategories)
        assertFalse(AssignmentOverrideReconciler.isHeldForReenable(prefs.pendingConflictCategories))
    }

    @Test
    fun `signed out, nothing is marked for whoever signs in next`() {
        // As after a sync, so the sign-in alone decides.
        prefs.hasSyncedAccount = true

        turnTigerSyncOn(signedIn = false)

        assertEquals(emptySet<String>(), prefs.pendingConflictCategories)
    }

    private companion object {
        val CATEGORIES = setOf("courses", "course_colors", "course_names", "assignments")
    }
}
