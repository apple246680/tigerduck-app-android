package org.ntust.app.tigerduck.data.preferences

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.model.AppFeature
import org.ntust.app.tigerduck.data.model.AppFeature.ANNOUNCEMENTS
import org.ntust.app.tigerduck.data.model.AppFeature.CALENDAR
import org.ntust.app.tigerduck.data.model.AppFeature.CLASS_TABLE
import org.ntust.app.tigerduck.data.model.AppFeature.HOME
import org.ntust.app.tigerduck.data.model.AppFeature.LIBRARY
import org.ntust.app.tigerduck.data.model.AppFeature.SCHOOL_MAIL
import org.ntust.app.tigerduck.data.model.AppFeature.SCORE
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [LibraryTabAddMigration] against the preferences an older build left behind:
 * the library opt-in switch, and a stored bottom bar or none.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LibraryTabAddMigrationTest {

    private lateinit var context: Context
    private lateinit var prefs: AppPreferences

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        raw().edit().clear().commit()
        prefs = AppPreferences(context)
    }

    private fun raw() = context.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE)

    private fun storeOptIn(on: Boolean) {
        raw().edit().putBoolean("libraryFeatureEnabled", on).commit()
    }

    @Test
    fun `a stored bar with room gets library left of more`() {
        prefs.configuredTabs = listOf(HOME, CLASS_TABLE, SCHOOL_MAIL)

        assertTrue(LibraryTabAddMigration.runIfNeeded(prefs))
        assertEquals(listOf(HOME, CLASS_TABLE, SCHOOL_MAIL, LIBRARY), prefs.configuredTabs)
    }

    @Test
    fun `a full bar stays as it is`() {
        val full = listOf(HOME, CLASS_TABLE, CALENDAR, SCORE)
        prefs.configuredTabs = full

        assertFalse(LibraryTabAddMigration.runIfNeeded(prefs))
        assertEquals(full, prefs.configuredTabs)
    }

    @Test
    fun `a user who had the library on and took it off the bar keeps their bar`() {
        storeOptIn(true)
        prefs.configuredTabs = listOf(HOME, ANNOUNCEMENTS)

        assertFalse(LibraryTabAddMigration.runIfNeeded(prefs))
        assertEquals(listOf(HOME, ANNOUNCEMENTS), prefs.configuredTabs)
    }

    @Test
    fun `a user who turned the library off gets it like anyone else`() {
        storeOptIn(false)
        prefs.configuredTabs = listOf(HOME, CLASS_TABLE)

        assertTrue(LibraryTabAddMigration.runIfNeeded(prefs))
        assertEquals(listOf(HOME, CLASS_TABLE, LIBRARY), prefs.configuredTabs)
    }

    @Test
    fun `an untouched bar stays unstored and follows the default, which has library`() {
        assertFalse(LibraryTabAddMigration.runIfNeeded(prefs))
        assertFalse(prefs.hasStoredConfiguredTabs)
        assertTrue(LIBRARY in prefs.configuredTabs)
        assertEquals(AppFeature.defaultTabs, prefs.configuredTabs)
    }

    @Test
    fun `a bar that already has library is not touched`() {
        prefs.configuredTabs = listOf(LIBRARY, HOME)

        assertFalse(LibraryTabAddMigration.runIfNeeded(prefs))
        assertEquals(listOf(LIBRARY, HOME), prefs.configuredTabs)
    }

    @Test
    fun `the opt-in switch is deleted`() {
        storeOptIn(true)

        LibraryTabAddMigration.runIfNeeded(prefs)

        assertFalse(raw().contains("libraryFeatureEnabled"))
    }

    @Test
    fun `it runs once, so a bar set up afterwards keeps library off`() {
        prefs.configuredTabs = listOf(HOME, CLASS_TABLE)
        LibraryTabAddMigration.runIfNeeded(prefs)
        prefs.configuredTabs = listOf(HOME, CLASS_TABLE)

        assertFalse(LibraryTabAddMigration.runIfNeeded(prefs))
        assertEquals(listOf(HOME, CLASS_TABLE), prefs.configuredTabs)
    }
}
