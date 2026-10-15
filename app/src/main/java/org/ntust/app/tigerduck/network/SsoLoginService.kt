package org.ntust.app.tigerduck.network

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.ntust.app.tigerduck.auth.StoredPasswordLatch
import org.ntust.app.tigerduck.auth.postAutomatically
import javax.inject.Inject
import javax.inject.Singleton

sealed class SsoLoginError : Exception() {
    class LoginFormNotFound : SsoLoginError()

    /** The school kept the sign-in on its form. [reason] is its message, when the page had one. */
    class LoginFailed(val reason: String? = null) : SsoLoginError()
    class InvalidResponse : SsoLoginError()
    data class NetworkError(val cause_: Exception) : SsoLoginError()

    /**
     * An automatic sign-in's credentials stopped being the stored account's while it ran, as a
     * sign-in by hand stored another password or the account signed out, so it posted nothing.
     */
    class AccountChanged : SsoLoginError()
}

/**
 * A page behind NTUST SSO, fetched with the session already held when
 * [sessionWarm] says there is one, and with one login and one retry when the
 * request is bounced to SSO anyway.
 *
 * [fetch] returns null for a bounce. [logIn] throws when it cannot log in.
 * Whatever the retry still cannot get past ends in [bounced], as does a
 * bounce after a cold session's login.
 *
 * A login costs a round of the OIDC bridge — the service root, the authorize
 * redirect, the bridge POST — even when the session it checks is fine, so
 * running one ahead of every fetch cost several requests where a warm
 * session needs one.
 * The retry is what makes skipping it safe: [sessionWarm] only says *a*
 * login succeeded within the hour, not that it was to this service.
 */
internal suspend fun <T> fetchWithSsoSession(
    sessionWarm: Boolean,
    logIn: suspend () -> Unit,
    fetch: suspend () -> T?,
    bounced: () -> Nothing,
): T {
    if (!sessionWarm) {
        // A bounce straight after a login of our own is not a stale session,
        // and a second login would only clear the cookies and do it again.
        logIn()
        return fetch() ?: bounced()
    }
    fetch()?.let { return it }
    logIn()
    return fetch() ?: bounced()
}

/**
 * [block] under the lock, unless [isStillWanted] says, once the lock is held, that whoever asked
 * no longer wants it; then [stale] without running it.
 */
internal suspend fun <T> Mutex.lockedUnlessStale(isStillWanted: () -> Boolean, stale: T, block: suspend () -> T): T =
    withLock { if (isStillWanted()) block() else stale }

@Singleton
class SsoLoginService internal constructor(
    private val sessionManager: NtustSessionManager,
    private val passwordLatch: StoredPasswordLatch,
    private val clientFor: () -> OkHttpClient,
) {
    @Inject
    constructor(sessionManager: NtustSessionManager, passwordLatch: StoredPasswordLatch) :
        this(sessionManager, passwordLatch, { sessionManager.client })

    private val client: OkHttpClient get() = clientFor()

    // One login at a time. A login that meets the SSO wall clears the whole
    // cookie jar (step 4) before it signs in, which pulled the session out
    // from under any other login running alongside — and Home, the class
    // table and the calendar each started one at the same moment on launch.
    private val loginMutex = Mutex()

    /**
     * Ensures the user is logged in to the given service via NTUST SSO, with
     * the stored account. Returns true on success, throws SsoLoginError on
     * failure. Once the school has refused the stored password, it posts it
     * no more and throws [SsoLoginError.LoginFailed] — see [StoredPasswordLatch].
     */
    suspend fun ensureServiceLogin(
        serviceUrl: String,
        studentId: String,
        password: String
    ): Boolean = loginMutex.withLock { logIn(serviceUrl, studentId, password) }

    /**
     * [ensureServiceLogin] for a caller whose account may sign out while it waits for the lock:
     * [isStillWanted] is asked once the lock is held, and a login no longer wanted returns false
     * without a request, rather than signing a departed account in again.
     */
    suspend fun ensureServiceLogin(
        serviceUrl: String,
        studentId: String,
        password: String,
        isStillWanted: () -> Boolean,
    ): Boolean = loginMutex.lockedUnlessStale(isStillWanted, stale = false) {
        logIn(serviceUrl, studentId, password)
    }

    /**
     * A sign-in with credentials just entered. [ensureServiceLogin] takes a
     * session the jar already holds as signed in, without submitting the
     * credentials; that is right for the account re-logging itself in, and
     * wrong for someone new, whose sign-in would then pass on the strength
     * of a session left by the account before. So this one empties the jar
     * first. Under the same lock, so a login still running for the account
     * that left finishes before the jar is emptied, not after.
     *
     * It posts the credentials whatever the latch holds, and leaves the latch
     * as it is: the refused password is still the one stored until the
     * sign-in stores the new one, which lifts it (TypedAccountSignIn).
     */
    suspend fun signIn(
        serviceUrl: String,
        studentId: String,
        password: String
    ): Boolean = loginMutex.withLock {
        sessionManager.invalidateSession()
        logIn(serviceUrl, studentId, password, typed = true)
    }

    /**
     * [typed] for credentials the user just entered. Any other login is one
     * the user did not start, and posts no password the school refused.
     */
    private suspend fun logIn(
        serviceUrl: String,
        studentId: String,
        password: String,
        typed: Boolean = false,
    ): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        // Before anything that could be refused, so a refusal that lands after
        // the user signed in again or signed out latches nothing.
        val latchGeneration = passwordLatch.generation

        // Step 1: Visit service URL
        var (html, url) = fetchPage(serviceUrl)

        // Step 2: Resolve OIDC bridge forms
        val resolved = resolveOIDCBridgeForms(html, url)
        html = resolved.first
        url = resolved.second

        // Step 3: Check if we're on SSO login page
        if (!HtmlParser.isSSOLoginPage(html, url)) {
            sessionManager.markLoginSuccess()
            return@withContext true
        }

        // The session needs the password now, and each refusal of it counts
        // toward the account's lockout. Before step 4, which would empty the
        // jar for a sign-in that is not going to happen.
        if (!typed) passwordLatch.rejection.value?.let { throw SsoLoginError.LoginFailed(it.reason) }

        // Step 4: Clear cookies and retry
        sessionManager.invalidateSession()

        val fresh = fetchPage(serviceUrl)
        html = fresh.first
        url = fresh.second

        if (!HtmlParser.isSSOLoginPage(html, url)) {
            // Capture the bridge result instead of discarding it: a bridge
            // POST that lands back on the SSO wall means we aren't actually
            // logged in, so we must not mark success — fall through to the
            // credential submission below.
            val (bridgeHtml, bridgeUrl) = resolveOIDCBridgeForms(html, url)
            if (!HtmlParser.isSSOLoginPage(bridgeHtml, bridgeUrl)) {
                sessionManager.markLoginSuccess()
                return@withContext true
            }
            html = bridgeHtml
            url = bridgeUrl
        }

        // Step 5: Submit login form
        val form = HtmlParser.findFormById(html, "loginForm")
            ?: throw SsoLoginError.LoginFormNotFound()

        val fields = form.inputs.toMutableList().apply {
            replaceOrAppend("Username", studentId)
            replaceOrAppend("Password", password)
            if (none { it.first == "captcha" }) add("captcha" to "")
        }

        val actionUrl = resolveUrl(form.action, url)
        // The credentials go to the school's sign-in host and nowhere else, whatever the page
        // names as the form's action. As on the iPhone (ssoActionHostAllowlist).
        if (!isSignInHost(actionUrl)) throw SsoLoginError.InvalidResponse()
        if (typed) return@withContext submitLoginForm(actionUrl, fields)
        // Under the lock every automatic post of the stored password takes, with the latch and
        // the stored account checked again right before the password goes out: a refusal
        // another sign-in got, the Moodle token renewal's included, has landed by then, and
        // none lands while this one is posting.
        passwordLatch.postAutomatically(
            generation = latchGeneration,
            studentId = studentId,
            password = password,
            refused = { SsoLoginError.LoginFailed(it.reason) },
            stale = { SsoLoginError.AccountChanged() },
        ) {
            try {
                submitLoginForm(actionUrl, fields)
            } catch (e: SsoLoginError.LoginFailed) {
                passwordLatch.markRejected(latchGeneration, studentId, password, e.reason)
                throw e
            }
        }
    }

    /**
     * Steps 5 to 7: posts the sign-in form's [fields] to [actionUrl] and follows the answer. Throws
     * [SsoLoginError.LoginFailed] with the school's message when the school kept the sign-in on its
     * form, and for nothing else.
     */
    private fun submitLoginForm(actionUrl: HttpUrl, fields: List<Pair<String, String>>): Boolean {
        var (html, url) = postLoginForm(actionUrl, fields)

        // Step 6: Resolve OIDC bridge forms after login
        val afterLogin = resolveOIDCBridgeForms(html, url)
        html = afterLogin.first
        url = afterLogin.second

        // Step 7: Check if still on SSO page
        if (HtmlParser.isSSOLoginPage(html, url)) {
            throw SsoLoginError.LoginFailed(HtmlParser.loginErrorMessage(html))
        }

        sessionManager.markLoginSuccess()
        return true
    }

    private fun fetchPage(url: String): Pair<String, HttpUrl> {
        try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                return body to (response.request.url)
            }
        } catch (e: SsoLoginError) {
            throw e
        } catch (e: java.io.IOException) {
            throw SsoLoginError.NetworkError(e)
        }
    }

    private fun resolveOIDCBridgeForms(
        html: String,
        url: HttpUrl,
        maxSteps: Int = 3
    ): Pair<String, HttpUrl> {
        var currentHtml = html
        var currentUrl = url
        repeat(maxSteps) {
            if (HtmlParser.isSSOLoginPage(currentHtml, currentUrl)) return currentHtml to currentUrl
            val form =
                HtmlParser.findOIDCBridgeForm(currentHtml) ?: return currentHtml to currentUrl
            val actionUrl = resolveUrl(form.action, currentUrl)
            val (newHtml, newUrl) = postForm(actionUrl, form.inputs)
            currentHtml = newHtml
            currentUrl = newUrl
        }
        return currentHtml to currentUrl
    }

    /**
     * The sign-in form's POST, which carries the password: no redirect sends the form on to
     * another address, and one that would ends the sign-in ([executeCredentialPost]).
     */
    private fun postLoginForm(url: HttpUrl, fields: List<Pair<String, String>>): Pair<String, HttpUrl> {
        try {
            val body = FormBody.Builder().apply {
                fields.forEach { (name, value) -> add(name, value) }
            }.build()
            val request = Request.Builder().url(url).post(body).build()
            val response = client.executeCredentialPost(request) ?: throw SsoLoginError.InvalidResponse()
            response.use { return it.body.string() to it.request.url }
        } catch (e: SsoLoginError) {
            throw e
        } catch (e: java.io.IOException) {
            throw SsoLoginError.NetworkError(e)
        }
    }

    private fun postForm(url: HttpUrl, fields: List<Pair<String, String>>): Pair<String, HttpUrl> {
        try {
            val body = FormBody.Builder().apply {
                fields.forEach { (name, value) -> add(name, value) }
            }.build()

            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val html = response.body.string()
                return html to response.request.url
            }
        } catch (e: SsoLoginError) {
            throw e
        } catch (e: java.io.IOException) {
            throw SsoLoginError.NetworkError(e)
        }
    }

    private fun resolveUrl(path: String, base: HttpUrl): HttpUrl {
        return try {
            if (path.startsWith("http://") || path.startsWith("https://")) {
                path.toHttpUrl()
            } else {
                base.newBuilder(path)?.build() ?: base
            }
        } catch (e: IllegalArgumentException) {
            base
        }
    }

    private fun MutableList<Pair<String, String>>.replaceOrAppend(name: String, value: String) {
        val idx = indexOfFirst { it.first == name }
        if (idx >= 0) this[idx] = name to value
        else add(name to value)
    }
}

/** Where the sign-in form may post the credentials: the school's sign-in host, over HTTPS. */
internal fun isSignInHost(url: HttpUrl): Boolean = url.isHttps && url.host == SIGN_IN_HOST

private const val SIGN_IN_HOST = "ssoam2.ntust.edu.tw"
