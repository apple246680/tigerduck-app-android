package org.ntust.app.tigerduck.mail

import java.net.URLEncoder

enum class ComposeMode { NEW, REPLY, REPLY_ALL, FORWARD, DRAFT }

/** Navigation routes for School Mail. Pure Kotlin so notifications and tests can build them. */
object MailRoutes {
    const val LIST = "schoolMail"
    const val MESSAGE = "schoolMail/message/{folder}/{uid}"
    const val COMPOSE = "schoolMail/compose?mode={mode}&folder={folder}&uid={uid}&mailto={mailto}"
    const val GUIDE = "schoolMail/guide"
    const val SETTINGS = "schoolMail/settings"

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun message(folder: String, uid: Long) = "schoolMail/message/${encode(folder)}/$uid"

    /** [mailto] is a whole `mailto:` link for a new mail to start from (see `MailtoLink`). */
    fun compose(mode: ComposeMode, folder: String? = null, uid: Long? = null, mailto: String? = null) =
        "schoolMail/compose?mode=${mode.name}&folder=${encode(folder.orEmpty())}&uid=${uid ?: -1}&mailto=${encode(mailto.orEmpty())}"
}
