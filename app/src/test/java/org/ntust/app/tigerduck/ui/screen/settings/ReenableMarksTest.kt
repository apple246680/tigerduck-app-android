package org.ntust.app.tigerduck.ui.screen.settings

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.DataMigration
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.ui.screen.home.AssignmentOverrideReconciler
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [markForReenableCheck]: which categories switched back on wait for the
 * re-enable check. A phone that has not synced a category of the signed-in
 * account holds none of its data, so the check has nothing to compare there,
 * and Use Local would send the emptied caches over the server's.
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

    // What HomeBackendSync.pull records once a full sync has run.
    private fun syncFully() {
        prefs.syncedCategories += categoriesSwitchedOn(prefs)
    }

    // What turning TigerSync on does with every category's switch on.
    private fun turnTigerSyncOn(signedIn: Boolean = true) {
        for (category in REENABLE_CATEGORIES) markForReenableCheck(prefs, category, signedIn)
    }

    @Test
    fun `a phone that has synced the account marks every category switched back on`() {
        syncFully()

        turnTigerSyncOn()

        assertEquals(REENABLE_CATEGORIES, prefs.pendingConflictCategories)
    }

    @Test
    fun `after a sign-out, the next sign-in marks nothing and its first sync takes the server's marks`() {
        syncFully()
        // What AuthService.logout clears.
        prefs.clearSyncStamps()

        turnTigerSyncOn()

        assertEquals(emptySet<String>(), prefs.pendingConflictCategories)
        assertFalse(AssignmentOverrideReconciler.isHeldForReenable(prefs.pendingConflictCategories))
    }

    @Test
    fun `a category switched off in every sync since the sign-in is left to its first sync`() {
        prefs.syncCourses = false
        prefs.syncCourseColors = false
        prefs.syncAssignments = false
        syncFully()

        // Courses back on, which takes their colours with them, and assignments.
        prefs.syncCourses = true
        prefs.syncCourseColors = true
        prefs.syncAssignments = true
        turnTigerSyncOn()

        assertEquals(setOf("course_names"), prefs.pendingConflictCategories)
    }

    @Test
    fun `signed out, nothing is marked for whoever signs in next`() {
        // As after a sync, so the sign-in alone decides.
        syncFully()

        turnTigerSyncOn(signedIn = false)

        assertEquals(emptySet<String>(), prefs.pendingConflictCategories)
    }

    @Test
    fun `an install signed in at the update keeps its re-enable checks`() {
        // Left by an older build, its check not run yet.
        prefs.pendingConflictCategories = setOf("assignments")
        // Off at the update, with whatever this phone changed since.
        prefs.syncCourses = false

        DataMigration.settleReenableCheck(prefs, signedIn = true)
        assertEquals(setOf("assignments"), prefs.pendingConflictCategories)

        turnTigerSyncOn()
        assertEquals(REENABLE_CATEGORIES, prefs.pendingConflictCategories)
    }

    @Test
    fun `a fresh install, or one signed out at the update, marks nothing before its first sync`() {
        DataMigration.settleReenableCheck(prefs, signedIn = false)

        // Signed in since.
        turnTigerSyncOn()

        assertEquals(emptySet<String>(), prefs.pendingConflictCategories)
    }

    @Test
    fun `an install signed out at the update drops the checks an older build left pending`() {
        // Marked while signed out, the check failing for want of a session.
        prefs.pendingConflictCategories = setOf("assignments", "courses")

        DataMigration.settleReenableCheck(prefs, signedIn = false)

        assertEquals(emptySet<String>(), prefs.pendingConflictCategories)
        assertFalse(AssignmentOverrideReconciler.isHeldForReenable(prefs.pendingConflictCategories))
    }
}
