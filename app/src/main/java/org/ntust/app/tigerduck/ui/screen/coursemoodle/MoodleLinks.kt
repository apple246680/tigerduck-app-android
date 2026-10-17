package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import org.ntust.app.tigerduck.mail.resolveAttachmentMimeType
import org.ntust.app.tigerduck.util.fileProviderAuthority
import org.ntust.app.tigerduck.util.logSummary
import java.io.File
import org.ntust.app.tigerduck.network.moodle.MoodleWebservice
import org.ntust.app.tigerduck.ui.openLink
import java.net.URLEncoder

/**
 * A page on the Moodle site, opened as every "Open in Moodle" opens one: in the Moodle app where
 * it is installed, else in the browser. Paths are site-relative.
 */
object MoodlePage {
    fun course(courseId: Int) = "/course/view.php?id=$courseId"
    fun forum(cmid: Int) = "/mod/forum/view.php?id=$cmid"
    fun discussion(discussionId: Int) = "/mod/forum/discuss.php?d=$discussionId"
    fun gradeReport(courseId: Int) = "/grade/report/user/index.php?id=$courseId"
    fun assignment(cmid: Int) = "/mod/assign/view.php?id=$cmid"
    fun assignments(courseId: Int) = "/mod/assign/index.php?id=$courseId"
    fun participants(courseId: Int) = "/user/index.php?id=$courseId"
    fun profile(userId: Int, courseId: Int) = "/user/view.php?id=$userId&course=$courseId"

    /**
     * The Moodle app's link to [path]. The page goes in `redirect` with `=`, `&` and `+` escaped,
     * as iOS escapes it, so a page with two parameters does not split the link's own query.
     */
    fun deepLink(path: String): String {
        val escaped = buildString {
            for (c in path) {
                when {
                    c == '=' || c == '&' || c == '+' || c == '#' || c == ' ' || c.code > 0x7E ->
                        append(URLEncoder.encode(c.toString(), "UTF-8"))
                    else -> append(c)
                }
            }
        }
        return "moodlemobile://https://${MoodleWebservice.SITE_HOST}?redirect=$escaped"
    }

    fun webUrl(path: String) = MoodleWebservice.SITE + path
}

/**
 * Opens [path] in the Moodle app through its `moodlemobile://` link, or in the browser when no
 * app takes it. Phase 2 signs the browser in through an autologin key, as iOS does.
 */
fun openMoodlePage(context: Context, path: String) {
    for (target in listOf(MoodlePage.deepLink(path), MoodlePage.webUrl(path))) {
        val intent = Intent(Intent.ACTION_VIEW, target.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        }
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
}

/**
 * Opens a link from Moodle content, such as an announcement's message: a Moodle page in the
 * browser, with a `webservice/pluginfile.php` link rewritten to the web path and its token
 * dropped, so no key leaves the app; any other web page as the Browser setting asks; mail as a
 * new message.
 */
fun openMoodleContentLink(context: Context, url: String, browserPreference: String) {
    val uri = url.toUri()
    when {
        uri.scheme.equals("mailto", ignoreCase = true) -> {
            runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        uri.host.equals(MoodleWebservice.SITE_HOST, ignoreCase = true) -> {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, moodleWebLink(url).toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        else -> openLink(context, url, browserPreference)
    }
}

/**
 * Opens a downloaded Moodle [file] in another app: a content URI to the app's own copy, readable
 * once by the app picked, so no link with the account's key ever leaves the app. A web page, an
 * SVG or any other XML, which a browser renders and may run scripts in, goes to the share sheet
 * as iOS sends it, not to a viewer, by the type listed or the one the copy's name gives. Through
 * the system's chooser, which says so itself when no app takes the file; the read grant rides the
 * wrapped intent's clip, which the chooser passes on.
 */
fun moodleFileIntent(
    context: Context,
    file: File,
    mimeType: String,
    extensionLookup: (String) -> String? = { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) },
): Intent {
    val uri = FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
    // The app picked asks the FileProvider for the type, which reads it from the copy's name.
    val nameType = file.extension.takeIf { it.isNotEmpty() }?.lowercase()?.let(extensionLookup)
    val sharedType = listOfNotNull(mimeType, nameType).firstOrNull(::isSharedNotViewed)
    val intent = if (sharedType != null) {
        Intent(Intent.ACTION_SEND).setTypeAndNormalize(sharedType).putExtra(Intent.EXTRA_STREAM, uri)
    } else {
        Intent(Intent.ACTION_VIEW).setDataAndTypeAndNormalize(uri, mimeType)
    }
    intent.clipData = ClipData.newRawUri(file.name, uri)
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return Intent.createChooser(intent, null)
}

/**
 * Opens [file] as [moodleFileIntent] says; whether it went. A file the app cannot hand out, or a
 * system with no chooser to take it, leaves a warning in the log naming only the failure's class.
 */
fun launchMoodleFile(context: Context, file: File, mimeType: String): Boolean = try {
    context.startActivity(moodleFileIntent(context, file, mimeType))
    true
} catch (e: RuntimeException) {
    Log.w(TAG, "attachment not opened: ${e.logSummary()}")
    false
}

private const val TAG = "MoodleLinks"

private fun isSharedNotViewed(mimeType: String): Boolean {
    val base = mimeType.substringBefore(';').trim().lowercase()
    return base in SHARED_NOT_VIEWED || base.endsWith("+xml")
}

private val SHARED_NOT_VIEWED = setOf("text/html", "text/xml", "application/xml")

/**
 * The type to open a Moodle file as: the one Moodle lists, unless that is its `document/unknown`
 * or none, when the file name's extension decides.
 */
fun moodleFileType(
    listed: String?,
    fileName: String,
    extensionLookup: (String) -> String? = { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) },
): String {
    val declared = listed?.takeUnless { it.substringBefore(';').trim().equals("document/unknown", ignoreCase = true) }
    return resolveAttachmentMimeType(declared.orEmpty(), fileName, extensionLookup)
}

/** [url] as the website serves it: the web-service file path becomes the web one, without a token. */
internal fun moodleWebLink(url: String): String {
    val uri = url.toUri()
    val path = uri.encodedPath.orEmpty()
    if (!path.startsWith("/webservice/pluginfile.php/")) return url
    val builder = uri.buildUpon().encodedPath(path.removePrefix("/webservice")).clearQuery()
    for (name in uri.queryParameterNames) {
        if (name == "token") continue
        uri.getQueryParameters(name).forEach { builder.appendQueryParameter(name, it) }
    }
    return builder.build().toString()
}
