package org.ntust.app.tigerduck.network.moodle

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Where a file a Moodle answer lists downloads from, as the Moodle app fetches it: under
 * `tokenpluginfile.php` with the account's private access key in the path, else the web-service
 * path with the token. Either key only ever goes to the Moodle site. Ported from iOS
 * `MoodleFileTransfer.downloadURL`.
 */
object MoodleFileUrls {

    /**
     * [fileUrl] with the key in it, or null for a file not served by the Moodle site's
     * `pluginfile.php`, which no key may be sent to.
     */
    fun downloadUrl(fileUrl: String, accessKey: String?, token: String): String? {
        val url = fileUrl.toHttpUrlOrNull() ?: return null
        if (url.scheme != "https" || url.host.lowercase() != MoodleWebservice.SITE_HOST) return null
        val segments = url.pathSegments
        val rest: List<String> = when {
            segments.size > 2 && segments[0] == "webservice" && segments[1] == "pluginfile.php" ->
                segments.drop(2)
            segments.size > 1 && segments[0] == "pluginfile.php" -> segments.drop(1)
            else -> return null
        }
        val builder = HttpUrl.Builder().scheme("https").host(url.host).port(url.port)
        val key = accessKey?.takeIf { it.isNotEmpty() }
        if (key != null) {
            builder.addPathSegment("tokenpluginfile.php").addPathSegment(key)
        } else {
            builder.addPathSegment("webservice").addPathSegment("pluginfile.php")
        }
        rest.forEach { builder.addPathSegment(it) }
        for (i in 0 until url.querySize) {
            val name = url.queryParameterName(i)
            if (name != "token") builder.addQueryParameter(name, url.queryParameterValue(i))
        }
        if (key == null) builder.addQueryParameter("token", token)
        return builder.build().toString()
    }
}
