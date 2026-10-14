package org.ntust.app.tigerduck.ui.screen.classtable.sharing

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Google's code scanner cannot open here, with no Play Services and no ML Kit started, as on a
 * phone where it fails for a moment: the scanner button stays, so the user can try again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PlayQrScannerCameraTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val scanLabel get() = RuntimeEnvironment.getApplication().getString(R.string.class_table_import)

    @Test
    fun `a scanner that fails to open keeps its button, and trying again fails the same way`() {
        composeRule.setContent {
            FlavorQrScannerCamera.Area(onScanned = { false }, onRead = {}, modifier = Modifier)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(scanLabel).assertExists().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(scanLabel).assertExists()
    }
}
