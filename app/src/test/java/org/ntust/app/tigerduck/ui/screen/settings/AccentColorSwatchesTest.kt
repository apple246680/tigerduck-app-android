package org.ntust.app.tigerduck.ui.screen.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What TalkBack says for the accent colour swatches in Settings. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AccentColorSwatchesTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var picked: Int? = null

    private fun show(selectedHex: Int, isDark: Boolean = false) {
        composeRule.setContent {
            AccentColorSwatches(selectedHex = selectedHex, isDark = isDark, onSelect = { picked = it })
        }
    }

    @Test
    fun `each swatch is named by its colour and the chosen one is selected`() {
        show(selectedHex = 0x007AFF)

        composeRule.onNodeWithContentDescription("Blue").assertIsSelected()
        composeRule.onNodeWithContentDescription("Purple").assertIsNotSelected()
        composeRule.onNodeWithContentDescription("Indigo").performScrollTo().assertIsNotSelected()
    }

    @Test
    fun `choosing a swatch in dark mode stores its light colour`() {
        show(selectedHex = 0x007AFF, isDark = true)

        composeRule.onNodeWithContentDescription("Cyan").performScrollTo().performClick()

        assertEquals(0x5AC8FA, picked)
    }
}
