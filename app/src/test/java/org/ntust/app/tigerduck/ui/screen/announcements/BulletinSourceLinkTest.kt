package org.ntust.app.tigerduck.ui.screen.announcements

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.browser.customtabs.CustomTabsIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [Build.VERSION_CODES.TIRAMISU])
class BulletinSourceLinkTest {
    // An activity, as on screen: an in-app tab needs one to start from.
    private val context = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private val url = "https://www.ntust.edu.tw/p/406-1000-1.php"

    @Test
    fun `the system browser setting opens the source in the browser, not an in-app tab`() {
        openBulletinSource(context, url, "system")
        val started = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(url, started.dataString)
        assertFalse(started.hasExtra(CustomTabsIntent.EXTRA_SESSION))
    }

    @Test
    fun `the in-app setting opens it in an in-app tab`() {
        openBulletinSource(context, url, "inApp")
        val started = shadowOf(context).nextStartedActivity
        assertEquals(url, started.dataString)
        assertTrue(started.hasExtra(CustomTabsIntent.EXTRA_SESSION))
    }
}
