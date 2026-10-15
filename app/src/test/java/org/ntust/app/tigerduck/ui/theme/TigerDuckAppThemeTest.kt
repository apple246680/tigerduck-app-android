package org.ntust.app.tigerduck.ui.theme

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The app's root theme is where every screen gets the interface style from. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class TigerDuckAppThemeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the root theme hands the picked style to the screens inside it`() {
        var seen: VisualStylePolicy? = null
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = false, visualPreset = VisualPreset.Tat) {
                seen = LocalVisualStylePolicy.current
            }
        }
        composeRule.waitForIdle()
        assertEquals(VisualPreset.Tat, seen?.preset)
    }

    @Test
    fun `the root theme hands TigerDuck when no style is given`() {
        var seen: VisualStylePolicy? = null
        composeRule.setContent {
            TigerDuckAppTheme(darkTheme = true) { seen = LocalVisualStylePolicy.current }
        }
        composeRule.waitForIdle()
        assertEquals(VisualPreset.TigerDuck, seen?.preset)
    }
}
