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
import java.io.IOException
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

    // When a refresh last got a 429 or a 5xx; read and written under refreshMutex.
    private var refreshPausedAt: Long? = null

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

    suspend fun login(
        studentId: String,
        password: String,
        moodleToken: String?,
        moodlePrivateToken: String?,
        platform: String = "android",
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
            tokens.save(accessToken, refreshToken, now() + expiresIn * 1000L)
            LoginResult(
                accessToken = accessToken,
                deviceId = result.getString("device_id"),
            )
        }
    }

    fun logout() {
        tokens.clear()
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

        val refreshToken = tokens.refreshToken ?: return null
        // Expires within 30 s, but works until then.
        val stillValid = access?.takeIf { now() < expiresAt }
        if (isRefreshPaused()) return stillValid
        // Finishes even when the caller is cancelled: stopped between a send
        // and what follows it, a token the backend may have rotated would stay
        // on disk for the next refresh.
        withContext(NonCancellable) { refreshAndSettle(refreshToken, stillValid) }
    }

    private suspend fun refreshAndSettle(refreshToken: String, stillValid: String?): String? {
        val outcome = try {
            withContext(Dispatchers.IO) { sendWithOneResend(refreshToken) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "refresh failed: ${e.javaClass.simpleName}")
            RefreshOutcome.Refused
        }
        when (outcome) {
            is RefreshOutcome.Refreshed -> {
                refreshPausedAt = null
                return outcome.accessToken
            }
            RefreshOutcome.Unavailable -> {
                refreshPausedAt = now()
                return stillValid
            }
            RefreshOutcome.NoReply, RefreshOutcome.Refused -> Unit
        }
        if (onRefreshFailed?.invoke() == true) return tokens.accessToken
        withContext(Dispatchers.IO) { logout() }
        return null
    }

    // Measured from the failure rather than to a deadline, so a clock set back
    // does not stretch the pause.
    private fun isRefreshPaused(): Boolean {
        val pausedAt = refreshPausedAt ?: return false
        return now() - pausedAt in 0 until REFRESH_PAUSE_MS
    }

    /**
     * Sends [refreshToken], and once more at once if no reply came back.
     *
     * A send whose reply is lost may still have rotated the token. Within 60 s
     * of a rotation the backend answers the old token once more with a fresh
     * pair, and takes any later send of it as reuse, so a resend that fails
     * too is a failed refresh, never a token kept for later.
     */
    private fun sendWithOneResend(refreshToken: String): RefreshOutcome {
        val first = send(refreshToken)
        if (first != RefreshOutcome.NoReply) return first
        return send(refreshToken) as? RefreshOutcome.Refreshed ?: RefreshOutcome.Refused
    }

    private fun send(refreshToken: String): RefreshOutcome {
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
                tokens.save(newAccess, newRefresh, now() + expiresIn * 1000L)
                RefreshOutcome.Refreshed(newAccess)
            }
        } catch (e: IOException) {
            Log.w(TAG, "refresh got no reply: ${e.javaClass.simpleName}")
            RefreshOutcome.NoReply
        }
    }

    private sealed interface RefreshOutcome {
        // Not a data class, whose toString() would print the token.
        class Refreshed(val accessToken: String) : RefreshOutcome

        /** No reply came back, so the backend may or may not have rotated the token. */
        data object NoReply : RefreshOutcome

        /** A 429 or a 5xx: the backend turned the refresh away, so the token is kept for a later one. */
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
