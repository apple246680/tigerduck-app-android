package org.ntust.app.tigerduck.auth

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.push.currentDeviceModel
import org.ntust.app.tigerduck.util.logSummary
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Manages v3 JWT access and refresh tokens.
 * Tokens are stored in EncryptedSharedPreferences via [V3TokenStore].
 */
class AuthTokenManager(
    private val tokens: V3TokenStore,
    private val httpClient: OkHttpClient,
    private val prefs: AppPreferences,
    private val deviceUuid: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val refreshMutex = Mutex()
    private val jsonType = "application/json".toMediaType()
    var onRefreshFailed: (suspend () -> Boolean)? = null

    // OkHttp sends a failed request again on its own, up to three sends of one
    // refresh token in all, and the backend takes a third send as reuse. The
    // timeout keeps the two sends [sendWithOneResend] makes inside the
    // backend's 60 s grace.
    private val refreshClient = httpClient.newBuilder()
        .retryOnConnectionFailure(false)
        .callTimeout(REFRESH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    // When a refresh last got a 429 or a 5xx, or could not be sent; read and
    // written under refreshMutex.
    private var refreshPausedAt: Long? = null

    // Held from reading generation through the token write that depends on
    // it, so a sign-out cannot fall between a reply's check and its save.
    private val signInLock = Any()
    private var generation = 0

    /**
     * Moved by [logout]. A login or refresh reply for an earlier value belongs
     * to an account that has signed out, so it stores nothing. Work that reads
     * the credentials and then suspends reads this first and passes it to
     * [login].
     */
    val signInGeneration: Int get() = synchronized(signInLock) { generation }

    // Resolve per-call so the debug API-endpoint override applies immediately,
    // matching PushApiClient — pinning at construction made login/refresh
    // traffic ignore an override that push traffic honored, minting tokens on
    // one host and presenting them to another.
    private val baseUrl: String
        get() = resolveAnnouncementEndpoint(prefs).url.trimEnd('/')

    val isLoggedIn: Boolean get() = tokens.refreshToken != null

    suspend fun authHeader(): String? {
        val access = validAccessToken() ?: return null
        return "Bearer $access"
    }

    /**
     * Non-suspending snapshot of the current bearer header, or null if no
     * access token is stored. Does NOT trigger a refresh — intended for
     * capturing the header before [logout] wipes the tokens, so a fire-and-
     * forget device-unregister DELETE can still authenticate.
     */
    fun currentAuthHeader(): String? =
        tokens.accessToken?.let { "Bearer $it" }

    /**
     * Signs in to the backend and stores the pair it returns. When [logout]
     * has moved [signInGeneration] past the value passed here, the reply
     * stores nothing and this throws [AuthException].
     */
    suspend fun login(
        studentId: String,
        password: String,
        moodleToken: String?,
        moodlePrivateToken: String?,
        platform: String = "android",
        signInGeneration: Int = this.signInGeneration,
    ): LoginResult = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("student_id", studentId)
            put("password", password)
            put("moodle_token", moodleToken)
            put("moodle_private_token", moodlePrivateToken)
            put("device_info", JSONObject().apply {
                put("client_device_id", deviceUuid)
                put("platform", platform)
                put("app_version", BuildConfig.VERSION_NAME)
                put("os_version", "Android ${android.os.Build.VERSION.RELEASE}")
                put("device_model", currentDeviceModel())
            })
        }
        val body = json.toString().toRequestBody(jsonType)
        val request = Request.Builder()
            .url("$baseUrl/auth/login")
            .post(body)
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw AuthException("login failed: ${response.code}")
            val result = JSONObject(response.body.string())
            val accessToken = result.getString("access_token")
            val refreshToken = result.getString("refresh_token")
            val expiresIn = result.getInt("expires_in")
            if (!saveIfSignedIn(signInGeneration, accessToken, refreshToken, now() + expiresIn * 1000L)) {
                throw AuthException("signed out during login")
            }
            LoginResult(
                accessToken = accessToken,
                deviceId = result.getString("device_id"),
            )
        }
    }

    /** Drops the pair, and keeps a login or refresh reply still on its way from storing one. */
    fun logout() {
        synchronized(signInLock) {
            generation++
            tokens.clear()
        }
    }

    /** Stores the pair unless [logout] has run since [signIn] was read, and says whether it did. */
    private fun saveIfSignedIn(signIn: Int, access: String, refresh: String, expiresAt: Long): Boolean {
        synchronized(signInLock) {
            if (generation != signIn) return false
            tokens.save(access, refresh, expiresAt)
            return true
        }
    }

    // Not logout(): the account has not signed out, so a sign-in another
    // caller has in flight may still store its pair.
    private fun clearIfSignedIn(signIn: Int) {
        synchronized(signInLock) {
            if (generation == signIn) tokens.clear()
        }
    }

    private suspend fun validAccessToken(): String? {
        val access = tokens.accessToken ?: return null
        val expiresAt = tokens.expiresAt
        if (now() < expiresAt - 30_000) return access
        return refresh()
    }

    private suspend fun refresh(): String? = refreshMutex.withLock {
        // Double-check after acquiring lock
        val access = tokens.accessToken
        val expiresAt = tokens.expiresAt
        if (access != null && now() < expiresAt - 30_000) return access

        // Before the refresh token: read after it, a sign-out between the two
        // reads would go unseen.
        val signIn = signInGeneration
        val refreshToken = tokens.refreshToken ?: return null
        // No header rather than the access token in its last seconds, which
        // would draw a 401, and Home answers a 401 with a relogin.
        if (isRefreshPaused()) return null
        // Finishes even when the caller is cancelled: stopped between a send
        // and what follows it, a token the backend may have rotated would stay
        // on disk for the next refresh.
        withContext(NonCancellable) { refreshAndSettle(refreshToken, signIn) }
    }

    private suspend fun refreshAndSettle(refreshToken: String, signIn: Int): String? {
        val outcome = try {
            withContext(Dispatchers.IO) { sendWithOneResend(refreshToken, signIn) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "refresh failed: ${e.javaClass.simpleName}")
            RefreshOutcome.Refused
        }
        // Signed out since the refresh began: what came back is the departing
        // account's, and a relogin or a clear would act on whoever signed in since.
        if (signIn != signInGeneration) return null
        when (outcome) {
            is RefreshOutcome.Refreshed -> {
                refreshPausedAt = null
                return outcome.accessToken
            }
            RefreshOutcome.Unavailable -> {
                refreshPausedAt = now()
                return null
            }
            RefreshOutcome.NoReply, RefreshOutcome.Refused -> Unit
        }
        if (onRefreshFailed?.invoke() == true) return accessTokenIfSignedIn(signIn)
        withContext(Dispatchers.IO) { clearIfSignedIn(signIn) }
        return null
    }

    // After a sign-out during the relogin, the stored token belongs to whoever
    // signed in since and is not this refresh's to hand out.
    private fun accessTokenIfSignedIn(signIn: Int): String? =
        synchronized(signInLock) { if (generation == signIn) tokens.accessToken else null }

    // Measured from the failure rather than to a deadline, so a clock set back
    // does not stretch the pause.
    private fun isRefreshPaused(): Boolean {
        val pausedAt = refreshPausedAt ?: return false
        return now() - pausedAt in 0 until REFRESH_PAUSE_MS
    }

    /**
     * Sends [refreshToken], and once more at once if no reply came back and
     * the account has not signed out since.
     *
     * A send whose reply is lost may still have rotated the token. Within 60 s
     * of a rotation the backend answers the old token once more with a fresh
     * pair, and takes any later send of it as reuse, so a resend that fails
     * too is a failed refresh, never a token kept for later.
     */
    private fun sendWithOneResend(refreshToken: String, signIn: Int): RefreshOutcome {
        val first = send(refreshToken, signIn)
        if (first != RefreshOutcome.NoReply || signIn != signInGeneration) return first
        return send(refreshToken, signIn) as? RefreshOutcome.Refreshed ?: RefreshOutcome.Refused
    }

    private fun send(refreshToken: String, signIn: Int): RefreshOutcome {
        val json = JSONObject().apply {
            put("refresh_token", refreshToken)
        }
        val body = json.toString().toRequestBody(jsonType)
        val request = Request.Builder()
            .url("$baseUrl/auth/refresh")
            .post(body)
            .build()
        return try {
            refreshClient.newCall(request).execute().use { response ->
                if (response.code == 429 || response.code >= 500) {
                    Log.w(TAG, "refresh got HTTP ${response.code}; keeping the tokens")
                    return@use RefreshOutcome.Unavailable
                }
                if (!response.isSuccessful) return@use RefreshOutcome.Refused
                val result = JSONObject(response.body.string())
                val newAccess = result.getString("access_token")
                val newRefresh = result.getString("refresh_token")
                val expiresIn = result.getInt("expires_in")
                // Stores nothing after a sign-out, which refreshAndSettle then sees.
                saveIfSignedIn(signIn, newAccess, newRefresh, now() + expiresIn * 1000L)
                RefreshOutcome.Refreshed(newAccess)
            }
        } catch (e: IOException) {
            if (e.wasNeverSent()) {
                Log.w(TAG, "refresh not sent: ${e.logSummary()}; keeping the tokens")
                RefreshOutcome.Unavailable
            } else {
                Log.w(TAG, "refresh got no reply: ${e.javaClass.simpleName}")
                RefreshOutcome.NoReply
            }
        }
    }

    // OkHttp 5 raises these before it writes the request: the DNS lookup's
    // UnknownHostException as is, and a refused or unreachable connect as a
    // ConnectException or NoRouteToHostException. A timeout or a reset may
    // come after the token went out, so either stays a NoReply.
    private fun IOException.wasNeverSent(): Boolean =
        this is UnknownHostException || this is ConnectException || this is NoRouteToHostException

    private sealed interface RefreshOutcome {
        // Not a data class, whose toString() would print the token.
        class Refreshed(val accessToken: String) : RefreshOutcome

        /** No reply came back, so the backend may or may not have rotated the token. */
        data object NoReply : RefreshOutcome

        /**
         * A 429 or a 5xx, or a send that never left the device: the backend did
         * not take the refresh, so the token is kept for a later one.
         */
        data object Unavailable : RefreshOutcome

        /** Anything else, a 401 among them: the app signs in again, as after every failed refresh before. */
        data object Refused : RefreshOutcome
    }

    data class LoginResult(val accessToken: String, val deviceId: String)
    class AuthException(message: String) : Exception(message)

    private companion object {
        const val TAG = "AuthTokenManager"
        const val REFRESH_TIMEOUT_SECONDS = 20L

        // The backend limits refreshes per IP over a minute.
        const val REFRESH_PAUSE_MS = 60_000L
    }
}
