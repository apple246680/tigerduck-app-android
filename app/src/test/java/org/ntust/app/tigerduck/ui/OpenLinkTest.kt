package org.ntust.app.tigerduck.ui

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.browser.customtabs.CustomTabsIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OpenLinkTest {

    private lateinit var context: Application
    private val url = "https://tigerduck.app/privacy-policy"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // Otherwise Robolectric "starts" any intent, whether or not an activity would handle it.
        shadowOf(context).checkActivities(true)
    }

    private fun installBrowser() {
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)),
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = "com.example.browser"
                    name = "com.example.browser.Main"
                }
            },
        )
    }

    private fun started(): Intent? = shadowOf(context).nextStartedActivity

    @Test
    fun `the system browser setting hands the link to the default browser`() {
        installBrowser()
        assertTrue(openLink(context, url, BrowserPreference.SYSTEM))
        val intent = started()!!
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(url, intent.dataString)
        assertFalse("not a Custom Tab", intent.hasExtra(CustomTabsIntent.EXTRA_SESSION))
    }

    @Test
    fun `the in-app setting opens a Custom Tab`() {
        installBrowser()
        assertTrue(openLink(context, url, BrowserPreference.IN_APP))
        val intent = started()!!
        assertEquals(url, intent.dataString)
        assertTrue("a Custom Tab", intent.hasExtra(CustomTabsIntent.EXTRA_SESSION))
    }

    @Test
    fun `with no browser on the phone it reports failure instead of throwing`() {
        listOf(BrowserPreference.SYSTEM, BrowserPreference.IN_APP).forEach { preference ->
            assertFalse(preference, openLink(context, url, preference))
            assertNull(preference, started())
        }
    }

    @Test
    fun `an application context gets a new task, which it cannot start an activity without`() {
        installBrowser()
        listOf(BrowserPreference.SYSTEM, BrowserPreference.IN_APP).forEach { preference ->
            assertTrue(preference, openLink(context, url, preference))
            assertTrue(preference, started()!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        }
    }
}
