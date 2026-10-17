package org.ntust.app.tigerduck.mail

import org.ntust.app.tigerduck.mail.compose.MailtoLink
import java.net.URLEncoder

enum class ComposeMode { NEW, REPLY, REPLY_ALL, FORWARD, DRAFT }

/** Navigation routes for School Mail. Pure Kotlin so notifications and tests can build them. */
object MailRoutes {
    const val LIST = "schoolMail"
    const val MESSAGE = "schoolMail/message/{folder}/{uid}"
    const val COMPOSE = "schoolMail/compose?mode={mode}&folder={folder}&uid={uid}&mailto={mailto}&to={to}"
    const val GUIDE = "schoolMail/guide"
    const val SETTINGS = "schoolMail/settings"

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun message(folder: String, uid: Long) = "schoolMail/message/${encode(folder)}/$uid"

    /**
     * [mailto] is a whole `mailto:` link for a new mail to start from, cut down by
     * [MailtoLink.capped] when it is too long to carry in a route. [to] prefills a new message's
     * recipients, as a course member's email does. Either rides along only when given.
     */
    fun compose(mode: ComposeMode, folder: String? = null, uid: Long? = null, mailto: String? = null, to: String? = null): String {
        var route = "schoolMail/compose?mode=${mode.name}&folder=${encode(folder.orEmpty())}&uid=${uid ?: -1}"
        if (!mailto.isNullOrBlank()) route += "&mailto=${encode(MailtoLink.capped(mailto))}"
        if (!to.isNullOrBlank()) route += "&to=${encode(to)}"
        return route
    }
}
