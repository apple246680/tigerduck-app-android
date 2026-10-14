package org.ntust.app.tigerduck.network.moodle

import android.util.Log
import com.google.gson.JsonElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.demo.DemoAccount
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.network.MoodleTokenService
import org.ntust.app.tigerduck.network.MoodleWebserviceError
import org.ntust.app.tigerduck.util.logSummary
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opens Moodle pages signed in, as the Moodle app does: `tool_mobile_get_autologin_key` trades the
 * stored private token for a one-time key, and Moodle's `autologin.php` signs the browser in with
 * it before it goes on to the page. Without a key the page opens as it is, signed out. Ported
 * from iOS `MoodleAutologinService`.
 *
 * The private token goes only in that request's body, to the Moodle site; the link the browser is
 * handed carries the one-time key, which Moodle accepts once.
 */
@Singleton
class MoodleAutologin internal constructor(
    private val keySource: suspend () -> Key?,
    private val scope: CoroutineScope,
    /** Real time, not the debug clock: Moodle counts [KEY_INTERVAL_MS] in real time. */
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * Moves on at every sign-out, read as it is now: the sign-out moves it where it runs, so a key
     * that arrives after one is dropped, as iOS reads its session's generation.
     */
    private val signInGeneration: () -> Int,
    /** Whether a private token is stored, which a sign-out clears where it runs. */
    private val hasPrivateToken: () -> Boolean,
) {
    @Inject
    constructor(
        webservice: MoodleWebservice,
        tokenService: MoodleTokenService,
        authTokenManager: AuthTokenManager,
        demoAccount: DemoAccount,
        @ApplicationScope appScope: CoroutineScope,
    ) : this(
        // The demo account has no network and no private token.
        keySource = {
            if (demoAccount.isActive) null else requestKey(webservice, tokenService::currentPrivateToken)
        },
        scope = appScope,
        signInGeneration = { authTokenManager.signInGeneration },
        hasPrivateToken = { tokenService.currentPrivateToken() != null },
    )

    data class Key(val userId: Int, val value: String, val autologinUrl: String)

    private val lock = Any()
    private var lastKeyAtMs: Long? = null
    private var requesting = false

    /** [url] behind a one-time sign-in where [signedIn] gives one, else [url]. */
    suspend fun url(url: String): String = signedIn(url) ?: url

    /**
     * [url] behind a one-time sign-in when it is a page [isSignInTarget] allows, else null, when
     * the page opens as it is. Without a key the page opens signed out rather than later: Moodle
     * refuses a key within [KEY_INTERVAL_MS] of the last one, a request already on its way gets
     * the only key coming, and the reader is waiting on the page, so a failed request is not
     * retried and a key slower than [KEY_WAIT_MS] is not waited for. A key that arrives after a
     * sign-out is dropped too: it would sign the browser in to the account that left.
     */
    suspend fun signedIn(url: String): String? {
        if (!isSignInTarget(url)) return null
        val generation = signInGeneration()
        val request = synchronized(lock) {
            if (requesting || !isKeyDue()) return null
            requesting = true
            startKeyRequest(generation)
        }
        val key = withTimeoutOrNull(KEY_WAIT_MS) { request.await() } ?: return null
        if (!isStillSignedIn(generation)) return null
        return signedInUrl(key, url)
    }

    /**
     * Asks for a key in the app's scope, where it runs to its end after the open that started it
     * stops waiting: Moodle starts [KEY_INTERVAL_MS] at the request, so a key that comes late
     * counts.
     */
    private fun startKeyRequest(generation: Int): Deferred<Key?> = scope.async {
        try {
            val key = try {
                keySource()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "autologin key unavailable: ${e.logSummary()}")
                null
            }
            if (key == null || key.value.isEmpty() || !isStillSignedIn(generation)) return@async null
            synchronized(lock) { lastKeyAtMs = now() }
            key
        } finally {
            synchronized(lock) { requesting = false }
        }
    }

    /** No sign-out since [generation] was read: the generation has not moved and the token is still stored. */
    private fun isStillSignedIn(generation: Int): Boolean = signInGeneration() == generation && hasPrivateToken()

    /** A last key stamped ahead of the clock, left by a clock set back, counts as old. */
    private fun isKeyDue(): Boolean {
        val last = lastKeyAtMs ?: return true
        val age = now() - last
        return age >= KEY_INTERVAL_MS || age < 0
    }

    companion object {
        private const val TAG = "MoodleAutologin"

        const val KEY_FUNCTION = "tool_mobile_get_autologin_key"

        /**
         * Moodle gives an account one key in this long (`autologinmintimebetweenreq`, 360 s on
         * NTUST) and refuses the rest, counting the keys the Moodle app asks for too.
         */
        const val KEY_INTERVAL_MS = 360_000L

        /**
         * How long an open waits for its key before the page opens signed out. A request held up
         * by a slow network or a token renewal could otherwise keep the reader waiting with
         * nothing on screen up to the request's timeout.
         */
        const val KEY_WAIT_MS = 3_000L

        /**
         * Whether [url] is a page the browser may be signed in for: https on the Moodle site
         * itself, on its own port and with no user in the address, and not one of Moodle's own
         * sign-in or app-launch pages, which a key has no business going to.
         */
        fun isSignInTarget(url: String): Boolean {
            val parsed = url.toHttpUrlOrNull() ?: return false
            if (parsed.scheme != "https" || parsed.port != 443) return false
            if (!parsed.host.equals(MoodleWebservice.SITE_HOST, ignoreCase = true)) return false
            if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
            val path = parsed.encodedPath.lowercase()
            return UNSIGNED_PATHS.none { path.startsWith(it) }
        }

        /** Moodle's sign-in pages and the app's own token and key pages. */
        private val UNSIGNED_PATHS = listOf("/login/", "/admin/tool/mobile/")

        /**
         * The `autologin.php` link from [key] with the account, the key and the page to go on to,
         * or null when that link is not https on the Moodle site, so the key never goes elsewhere.
         */
        fun signedInUrl(key: Key, url: String): String? {
            val link = key.autologinUrl.toHttpUrlOrNull() ?: return null
            if (link.scheme != "https" || !link.host.equals(MoodleWebservice.SITE_HOST, ignoreCase = true)) return null
            return link.newBuilder()
                .query(null)
                .addQueryParameter("userid", key.userId.toString())
                .addQueryParameter("key", key.value)
                .addQueryParameter("urltogo", url)
                .build()
                .toString()
        }

        /**
         * Asks Moodle for a key under the stored token, renewed as for any Moodle call. The private
         * token goes in the POST body, since Moodle refuses one in the query, and is read again
         * inside, as a renewed token comes with its own. Null without a private token.
         */
        suspend fun requestKey(webservice: MoodleWebservice, privateToken: () -> String?): Key? {
            if (privateToken() == null) return null
            return webservice.withToken { token ->
                val secret = privateToken() ?: return@withToken null
                val userId = webservice.siteInfo(token).userId
                key(webservice.call(KEY_FUNCTION, token, listOf("privatetoken" to secret)), userId)
            }
        }

        internal fun key(json: JsonElement, userId: Int): Key {
            val o = json.takeIf { it.isJsonObject }?.asJsonObject
                ?: throw MoodleWebserviceError.MalformedResponse("autologin key not an object")
            fun text(name: String): String = o.get(name)?.takeIf { it.isJsonPrimitive }?.asString
                ?: throw MoodleWebserviceError.MalformedResponse("$name missing")
            return Key(userId = userId, value = text("key"), autologinUrl = text("autologinurl"))
        }
    }
}
