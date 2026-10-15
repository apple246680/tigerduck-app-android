package org.ntust.app.tigerduck.mail

import java.net.URLEncoder

enum class ComposeMode { NEW, REPLY, REPLY_ALL, FORWARD, DRAFT }

/** Navigation routes for School Mail. Pure Kotlin so notifications and tests can build them. */
object MailRoutes {
    const val LIST = "schoolMail"
    const val MESSAGE = "schoolMail/message/{folder}/{uid}"
    const val COMPOSE = "schoolMail/compose?mode={mode}&folder={folder}&uid={uid}&to={to}"
    const val GUIDE = "schoolMail/guide"
    const val SETTINGS = "schoolMail/settings"

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun message(folder: String, uid: Long) = "schoolMail/message/${encode(folder)}/$uid"

    /** [to] prefills a new message's recipients, as a course member's email does. */
    fun compose(mode: ComposeMode, folder: String? = null, uid: Long? = null, to: String? = null): String {
        val base = "schoolMail/compose?mode=${mode.name}&folder=${encode(folder.orEmpty())}&uid=${uid ?: -1}"
        return if (to.isNullOrBlank()) base else "$base&to=${encode(to)}"
    }
}
