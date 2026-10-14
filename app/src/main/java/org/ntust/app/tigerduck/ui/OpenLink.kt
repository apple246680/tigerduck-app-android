package org.ntust.app.tigerduck.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri

/** The values Settings > "Open links with" stores in [AppState.browserPreference]. */
object BrowserPreference {
    const val SYSTEM = "system"
    const val IN_APP = "inApp"
}

/**
 * Opens [url] the way "Open links with" says: a Custom Tab for
 * [BrowserPreference.IN_APP], the default browser otherwise. The one opener
 * every web link in the app goes through.
 *
 * Returns false rather than crashing when nothing on the phone can open the
 * link: some work profiles, kiosk and de-Googled ROMs have no browser at all.
 */
fun openLink(context: Context, url: String, browserPreference: String): Boolean {
    val uri = url.toUri()
    // Outside an Activity there is no task to put the browser in.
    fun Intent.inATask() = apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    return try {
        if (browserPreference == BrowserPreference.IN_APP) {
            CustomTabsIntent.Builder().build().apply { intent.inATask() }.launchUrl(context, uri)
        } else {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).inATask())
        }
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        // A handler that is not exported to us: as unopenable as no handler at all.
        false
    }
}
