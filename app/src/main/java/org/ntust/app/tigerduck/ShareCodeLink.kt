package org.ntust.app.tigerduck

import android.content.Intent
import android.net.Uri
import org.ntust.app.tigerduck.data.sharing.ClassTableShareCode

/**
 * A class table share code opened as a link rather than scanned in the app. A share code is a
 * URL, `HTTPS://TIGERDUCK.APP/T/<code>`, which App Links hand to the app when the camera, Google
 * Lens or another QR app reads one, or when one is tapped as a link (the manifest's autoVerify
 * filter); tigerduck.app's "Open in TigerDuck" button opens `tigerduck://share/<code>`.
 *
 * Any web page or app can open either, not only a QR code, so a link does no more than a scan:
 * the import reads it as the camera's code, and nothing is saved before its confirm step.
 *
 * Android matches the manifest filter's scheme and host case-sensitively, and share codes are
 * written in upper case, so a scanner that starts the URL exactly as written may open the browser
 * instead; the web page's button then opens the app. The manifest has no upper-case filter, which
 * verification would not cover. A link that does arrive reads the same in any letter case.
 */
internal object ShareCodeLink {
    /** `tigerduck://share/<code>`, beside the [AppLink] hosts in the manifest. */
    const val HOST = "share"

    /** Where a share code's URL points, as App Links deliver it; the decoder ignores letter case. */
    private const val WEB_PREFIX = "${AppUrls.WEBSITE}/T/"

    /**
     * The text the import reads for [link], an intent's data: a share code URL as it came, in
     * whatever letter case, as the decoder trims and upper-cases it itself; and
     * `tigerduck://share/<code>` as that URL, `https://tigerduck.app/T/<code>`. Null for any other
     * link, which the rest of the app reads.
     *
     * Anything under `/T/` counts, so a URL that holds no share code still reaches the decoder,
     * and the import says the code is damaged rather than opening nothing.
     */
    fun codeText(link: Uri?): String? {
        link ?: return null
        val text = link.toString()
        return when {
            text.startsWith(ClassTableShareCode.PREFIX, ignoreCase = true) -> text
            // Matched exactly, as the manifest's filter and AppLink.parse match a tigerduck:// link.
            link.scheme == AppLink.SCHEME && link.host == HOST ->
                WEB_PREFIX + link.encodedPath.orEmpty().removePrefix("/")
            else -> null
        }
    }
}

/**
 * The share code text [intent]'s link carries, for the import to read now, or null. The link is
 * taken off [intent] either way, so it opens once per delivery; and none opens for an Activity
 * rebuilt from saved state ([restored]) or relaunched from Recents, which hand back the intent it
 * was first started with. A link of any other kind is left on [intent].
 *
 * Nor does one open unless [mayImport]: someone signed in, onboarding finished. The code is not
 * kept for later then (decided for both apps): the link only opens the app, and the student scans
 * the code again after signing in.
 */
internal fun takeSharedClassTableCode(intent: Intent, restored: Boolean, mayImport: Boolean): String? {
    val text = ShareCodeLink.codeText(intent.data) ?: return null
    intent.data = null
    val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
    return text.takeIf { mayImport && !restored && !fromHistory }
}
