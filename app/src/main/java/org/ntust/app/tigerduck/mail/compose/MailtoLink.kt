package org.ntust.app.tigerduck.mail.compose

import java.net.URLDecoder

/**
 * A `mailto:` link (RFC 6068) as the fields of a new mail: the addresses in its path and its `to`
 * query, its `cc`, `subject` and `body`. A `bcc` is dropped on purpose: a link in a mail must not
 * add a recipient the sender never sees on the compose screen without opening the Cc/Bcc fields.
 */
object MailtoLink {
    /** Recipients as one comma-separated line each, the form the compose fields take. */
    data class Draft(val to: String, val cc: String, val subject: String, val body: String)

    fun parse(href: String): Draft? {
        if (!href.startsWith(SCHEME, ignoreCase = true)) return null
        val rest = href.substring(SCHEME.length)
        val query = rest.substringAfter('?', "")
            .split('&')
            .filter { it.isNotEmpty() }
            .map { it.substringBefore('=').lowercase() to decode(it.substringAfter('=', "")) }
        fun values(key: String) = query.filter { it.first == key }.map { it.second }
        fun addresses(vararg parts: String) = parts.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
        return Draft(
            to = addresses(decode(rest.substringBefore('?')), *values("to").toTypedArray()),
            cc = addresses(*values("cc").toTypedArray()),
            subject = values("subject").firstOrNull().orEmpty(),
            body = values("body").firstOrNull().orEmpty().replace("\r\n", "\n"),
        )
    }

    /** Percent-decoding only: in a mailto link a `+` is a plus sign, not a space. */
    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)

    private const val SCHEME = "mailto:"
}
