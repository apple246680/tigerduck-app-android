package org.ntust.app.tigerduck

import android.app.Application
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.mail.MailRoutes
import org.ntust.app.tigerduck.ui.navigation.Screen
import org.ntust.app.tigerduck.widget.LibraryShortcutWidget
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which `start_route` extras MainActivity follows. It is exported, so any app
 * can put one on an intent: only the routes the app's own widgets and
 * notifications use may come through, and anything else must be dropped,
 * never handed to the NavHost.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class StartRouteTest {

    private fun routeFor(extra: String?, link: String? = null): String? = startRouteOf(
        Intent().apply {
            if (extra != null) putExtra("start_route", extra)
            if (link != null) data = Uri.parse(link)
        }
    )

    @Test
    fun `the routes the app's widgets and notifications use come through`() {
        assertEquals(Screen.ClassTable.route, routeFor(Screen.ClassTable.route))
        assertEquals(LibraryShortcutWidget.ROUTE_SENTINEL, routeFor(LibraryShortcutWidget.ROUTE_SENTINEL))
        assertEquals(MailRoutes.LIST, routeFor(MailRoutes.LIST))
        for (folder in listOf("INBOX", "INBOX.Sent", "[Gmail]/Sent Mail", "寄件備份")) {
            val route = MailRoutes.message(folder, 42)
            assertEquals(folder, route, routeFor(route))
        }
    }

    @Test
    fun `any other route is dropped`() {
        listOf(
            Screen.Settings.route,
            Screen.Debug.route,
            Screen.NotificationDebug.route,
            Screen.ApiEndpointDebug.route,
            Screen.CloudSync.route,
            Screen.AnnouncementDetail.route(1),
            MailRoutes.compose(org.ntust.app.tigerduck.mail.ComposeMode.NEW),
            MailRoutes.SETTINGS,
            "no-such-route",
            "classTable/x",
            "schoolMail/message/INBOX/abc",
            "schoolMail/message/INBOX/-1",
            "schoolMail/message/a/b/1",
            "schoolMail/message//1",
            "schoolMail/message/%ZZ/1",
            "schoolMail/message/INBOX/1?x=1",
            "",
        ).forEach { assertNull(it, routeFor(it)) }
    }

    @Test
    fun `a dropped route leaves the intent's link to open`() {
        assertEquals(Screen.ClassTable.route, routeFor("debug", link = "tigerduck://classtable"))
        assertNull(routeFor(null, link = "tigerduck://home"))
    }
}
