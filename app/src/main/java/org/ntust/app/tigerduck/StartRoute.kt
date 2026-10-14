package org.ntust.app.tigerduck

import org.ntust.app.tigerduck.mail.MailRoutes
import org.ntust.app.tigerduck.ui.navigation.Screen
import org.ntust.app.tigerduck.widget.LibraryShortcutWidget

/**
 * The [EXTRA] the app's own widgets and notifications put on a [MainActivity]
 * intent to open a screen, and the routes it may name.
 *
 * MainActivity is exported, so the extra can arrive on any app's intent too.
 * [accepted] lets through exactly the routes the app itself sends — the class
 * table and Library widgets, and School Mail's notifications — and drops
 * everything else, so no other screen can be reached this way and a route the
 * NavHost does not have never reaches it.
 */
object StartRoute {
    const val EXTRA = "start_route"

    private val FIXED = setOf(
        // BaseClassTableWidget
        Screen.ClassTable.route,
        // LibraryShortcutWidget, resolved against the library switch on arrival
        LibraryShortcutWidget.ROUTE_SENTINEL,
        // AndroidMailNotifier's summary, and the developer menu's preview of it
        MailRoutes.LIST,
    )

    /**
     * AndroidMailNotifier's per-mail tap, [MailRoutes.message]: a folder as
     * URLEncoder writes it, then a UID.
     */
    private val MAIL_MESSAGE = Regex("""schoolMail/message/(?:[A-Za-z0-9._*\-]|%[0-9A-Fa-f]{2})+/\d{1,19}""")

    /** [route] when it is one the app sends, else null. */
    fun accepted(route: String?): String? =
        route?.takeIf { it in FIXED || MAIL_MESSAGE.matches(it) }
}
