package org.ntust.app.tigerduck.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Executes [request], the POST of a sign-in form that carries the password, so that the form goes
 * only where it was addressed. OkHttp follows redirects on its own, and for a 307 or a 308 it
 * sends the same form again to wherever the redirect points; this POST follows none itself. A 301,
 * 302 or 303 is then followed by hand as a GET, which carries no form, and only to an HTTPS
 * address; the client follows whatever comes after that GET as it always does. A 307 or a 308,
 * any other redirect, or one with no usable address returns null, the form not sent again. The
 * caller closes the response it gets.
 */
internal fun OkHttpClient.executeCredentialPost(request: Request): Response? {
    val response = newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
        .newCall(request)
        .execute()
    if (!response.isRedirect) return response
    response.use {
        if (it.code !in FOLLOWED_AS_GET) return null
        val next = it.header("Location")?.let(request.url::resolve) ?: return null
        if (!next.isHttps) return null
        return newCall(request.newBuilder().url(next).get().build()).execute()
    }
}

/** The redirects a browser follows after a form's POST with a GET, the form left behind. */
private val FOLLOWED_AS_GET = setOf(301, 302, 303)
