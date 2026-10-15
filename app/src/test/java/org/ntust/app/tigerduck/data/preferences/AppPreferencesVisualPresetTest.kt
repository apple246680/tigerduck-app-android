package org.ntust.app.tigerduck.data.preferences

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.ui.theme.VisualPreset
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The interface style picked in Settings > Display, as it is stored and read back. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class AppPreferencesVisualPresetTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `an install that never picked a style reads TigerDuck`() {
        assertEquals(VisualPreset.TigerDuck, AppPreferences(context).visualPreset)
    }

    @Test
    fun `a picked style is stored and survives a restart`() {
        AppPreferences(context).visualPreset = VisualPreset.Tat

        assertEquals(VisualPreset.Tat, AppPreferences(context).visualPreset)
        // The same key and string the iPhone app uses for the same style.
        assertEquals(
            "tat",
            context.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE).getString("visualPreset", null),
        )
    }

    @Test
    fun `a style this build does not know reads as TigerDuck`() {
        context.getSharedPreferences("tigerduck_prefs", Context.MODE_PRIVATE).edit()
            .putString("visualPreset", "someLaterStyle").commit()
        assertEquals(VisualPreset.TigerDuck, AppPreferences(context).visualPreset)
    }

    @Test
    fun `a full reset puts the style back to TigerDuck`() {
        val prefs = AppPreferences(context)
        prefs.visualPreset = VisualPreset.Tat
        prefs.clearAllPrefs()
        assertEquals(VisualPreset.TigerDuck, prefs.visualPreset)
    }
}
