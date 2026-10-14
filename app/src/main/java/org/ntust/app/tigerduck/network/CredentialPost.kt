package org.ntust.app.tigerduck.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Executes [request], the POST of a sign-in form that carries the password, so that the form goes
 * only where it was addressed, and once. OkHttp follows redirects on its own, and for a 307 or a
 * 308 it sends the same form again to wherever the redirect points; this POST follows none itself,
 * nor is it sent again after a connection failure.
 *
 * A 307 or a 308 is followed with the same form only to the school's sign-in host itself, over
 * HTTPS, as a load balancer in front of it may send. A 301, 302 or 303 is followed by hand as a
 * GET, which carries no form, and only to an HTTPS address; the client follows whatever comes
 * after that GET as it always does. Any other redirect, or one with no usable address, returns
 * null, the form not sent again. The caller closes the response it gets.
 */
internal fun OkHttpClient.executeCredentialPost(request: Request): Response? {
    val formClient = newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        // A connection that drops once the form is out may have delivered it: sent again, the
        // password would count twice toward the account's lockout.
        .retryOnConnectionFailure(false)
        .build()
    var form = request
    repeat(MAX_FORM_REDIRECTS + 1) {
        val response = formClient.newCall(form).execute()
        if (!response.isRedirect) return response
        response.use {
            val next = it.header("Location")?.let(it.request.url::resolve) ?: return null
            // The request the POST went out as, not the caller's: it carries the account the
            // sign-in started under (AccountSessionGuard), which a sign-out since then makes stale.
            val sent = it.request
            when {
                it.code in FOLLOWED_AS_GET && next.isHttps ->
                    return newCall(sent.newBuilder().url(next).get().build()).execute()
                it.code in FOLLOWED_WITH_FORM && isSignInHost(next) ->
                    form = sent.newBuilder().url(next).build()
                else -> return null
            }
        }
    }
    return null
}

/** The redirects a browser follows after a form's POST with a GET, the form left behind. */
private val FOLLOWED_AS_GET = setOf(301, 302, 303)

/** The redirects that keep the method and the form. */
private val FOLLOWED_WITH_FORM = setOf(307, 308)

/** How many times the sign-in host may send the form on to itself before it counts as a loop. */
private const val MAX_FORM_REDIRECTS = 3
