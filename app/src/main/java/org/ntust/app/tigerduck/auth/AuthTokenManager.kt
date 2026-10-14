package org.ntust.app.tigerduck.auth

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.ntust.app.tigerduck.BuildConfig
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.push.OwedUnregister
import org.ntust.app.tigerduck.push.currentDeviceModel
import org.ntust.app.tigerduck.util.logSummary
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** A request that needs a session got no access token, so it was not sent. */
class NotSignedInException : Exception("not authenticated")

/**
 * Manages v3 JWT access and refresh tokens, and signs in again on its own
 * when they are refused or gone.
 * Tokens are stored in EncryptedSharedPreferences via [V3TokenStore].
 */
class AuthTokenManager(
    private val tokens: V3TokenStore,
    private val httpClient: OkHttpClient,
    private val prefs: AppPreferences,
    private val deviceUuid: String,
    private val now: () -> Long = System::currentTimeMillis,
    // The unregister a sign-out owes, which a login waits for and then ends; see [login].
    private val owedUnregister: OwedUnregister? = null,
    // How long a departing refresh waits for a session refresh of its token on its way.
    private val departingWaitMs: Long = REFRESH_TIMEOUT_SECONDS * 1000,
) {
    private val refreshMutex = Mutex()
    private val jsonType = "application/json".toMediaType()

    /**
     * Signs in with the stored account, passing the generation it is given to
     * [login], and says what it sent: AuthService's. Run only by the automatic
     * sign-in: after a refused refresh, for [signInAgain], and when something
     * finds no session.
     */
    var signInWithStoredAccount: (suspend (signInGeneration: Int) -> SignInAttempt)? = null

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

    // Under signInLock: the sign-in that automatic callers which overlap it
    // wait for, and when one that sent credentials last failed.
    private var signInUnderWay: CompletableDeferred<Boolean>? = null
    private var signInFailedAt: Long? = null

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
     * [authHeader] for a request that needs a session. Without one the request
     * is not sent, as the backend could only refuse it with 401.
     */
    suspend fun requireAuthHeader(): String = authHeader() ?: throw NotSignedInException()

    /**
     * Signs in when there is no session, and says whether there is one now.
     * Runs on each return to the app, before a registration or a sync, and
     * from [authHeader].
     */
    suspend fun signInIfSignedOut(): Boolean {
        if (!isLoggedIn) signInAutomatically()
        return isLoggedIn
    }

    /** Signs in again after the backend refused a session that looked valid, and says whether it did. */
    suspend fun signInAgain(): Boolean = signInAutomatically()

    /**
     * Runs the interactive sign-in's [signIn], which calls [login] with the
     * generation it is given, and says whether it signed in. It never waits
     * for the pause. An automatic sign-in that starts meanwhile waits for it
     * instead of logging in too, and a failure starts the pause as an
     * automatic one does.
     */
    suspend fun signInInteractively(signIn: suspend (signInGeneration: Int) -> SignInAttempt): Boolean {
        val attempt = CompletableDeferred<Boolean>()
        val current = synchronized(signInLock) {
            signInUnderWay = attempt
            generation
        }
        return runSignIn(attempt, current, signIn)
    }

    /**
     * Runs [signInWithStoredAccount], or waits for the sign-in under way. For
     * [SIGN_IN_PAUSE_MS] after one with credentials to send failed, it makes
     * none and returns false: the backend allows 5 logins per 15 minutes per
     * student and per IP, and a campus network puts many students behind one
     * IP.
     */
    private suspend fun signInAutomatically(): Boolean {
        val signInWith = signInWithStoredAccount ?: return false
        val attempt = CompletableDeferred<Boolean>()
        val signIn: Int
        // The sign-in already under way, which this caller waits for instead.
        val running = synchronized(signInLock) {
            // Before the credentials, which the sign-in reads.
            signIn = generation
            signInUnderWay ?: run {
                if (isSignInPaused()) return false
                signInUnderWay = attempt
                null
            }
        }
        if (running != null) return running.await()
        // Finishes for the callers waiting on it when this one is cancelled.
        return withContext(NonCancellable) {
            runSignIn(attempt, signIn) { generation ->
                // A failure of the sign-in itself is the caller's no session,
                // never an exception out of its token lookup.
                try {
                    signInWith(generation)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "sign-in failed: ${e.logSummary()}")
                    SignInAttempt.FAILED
                }
            }
        }
    }

    /** Runs [signInWith] as the sign-in under way, which [attempt] tells its waiting callers about. */
    private suspend fun runSignIn(
        attempt: CompletableDeferred<Boolean>,
        signIn: Int,
        signInWith: suspend (Int) -> SignInAttempt,
    ): Boolean {
        var signedIn = false
        try {
            signedIn = settleSignIn(signIn, signInWith(signIn))
        } finally {
            synchronized(signInLock) { if (signInUnderWay === attempt) signInUnderWay = null }
            attempt.complete(signedIn)
        }
        return signedIn
    }

    private fun settleSignIn(signIn: Int, attempt: SignInAttempt): Boolean = synchronized(signInLock) {
        // Signed out since: the attempt was the departing account's, and the
        // sign-out ended any pause.
        if (generation != signIn) return@synchronized false
        when (attempt) {
            SignInAttempt.SIGNED_IN -> signInFailedAt = null
            SignInAttempt.FAILED -> signInFailedAt = now()
            SignInAttempt.NOTHING_TO_SEND -> Unit
        }
        attempt == SignInAttempt.SIGNED_IN
    }

    // Measured from the failure, as the refresh pause is, so a clock set back
    // does not stretch it.
    private fun isSignInPaused(): Boolean {
        val failedAt = signInFailedAt ?: return false
        return now() - failedAt in 0 until SIGN_IN_PAUSE_MS
    }

    /**
     * The session as it stands, for a sign-out to take before [logout] wipes
     * it: the device unregister goes out as the departing account after. Null
     * without one.
     */
    fun departingSession(): DepartingSession? = synchronized(signInLock) {
        val access = tokens.accessToken ?: return null
        val refresh = tokens.refreshToken ?: return null
        // The backend recorded at sign-in; the one set now for a pair stored before that was.
        DepartingSession(access, refresh, tokens.expiresAt, tokens.issuedBy ?: baseUrl, tokens.studentId)
    }

    /**
     * Refreshes a [DepartingSession] for the sign-out's device unregister.
     * Stores nothing: the pair is the departing account's, and the session
     * here is whoever signs in next. Sent once more at once when no reply came
     * back, and a resend that does not refresh is [DepartingRefresh.Refused],
     * as [sendWithOneResend] has it: the token may have been rotated, and a
     * later send of it would be taken for reuse.
     */
    suspend fun refreshDeparting(departing: DepartingSession): DepartingRefresh {
        val token = departing.refreshToken
        // The session's own refresh of this token may still be on its way, sent
        // before the sign-out: its pair is waited for and taken rather than the
        // token sent once more. That refresh belongs to the departing account,
        // so it signs nobody in and never waits on the caller.
        val onItsWay = synchronized(signInLock) { refreshOnItsWay?.takeIf { it.first == token }?.second }
        // Bounded, as whoever waits on the unregister waits on this: past it the
        // refresh is left for a later try rather than the token sent alongside.
        if (onItsWay != null && withTimeoutOrNull(departingWaitMs) { onItsWay.await() } == null) {
            return DepartingRefresh.Unavailable
        }
        return withContext(Dispatchers.IO) {
            // To the backend that issued the session, whatever the endpoint setting says now.
            val base = departing.baseUrl
            val outcome = takeUnstoredPair(token) ?: run {
                var sent: RefreshOutcome
                do {
                    // Two sends without a reply already, this one's or the
                    // session's: a third would be taken for reuse.
                    if (sendsWithoutReply(token) >= 2) return@run RefreshOutcome.Refused
                    sent = send(token, DEPARTING, base)
                } while (sent == RefreshOutcome.NoReply)
                sent
            }
            when (outcome) {
                is RefreshOutcome.Refreshed -> DepartingRefresh.Refreshed(
                    DepartingSession(outcome.accessToken, outcome.refreshToken, outcome.expiresAt, base),
                )
                RefreshOutcome.Refused, RefreshOutcome.NoReply -> DepartingRefresh.Refused
                RefreshOutcome.Unavailable -> DepartingRefresh.Unavailable
            }
        }
    }

    // Under signInLock. The pair a refresh got back after a sign-out, which it
    // could not store, by the token it replaced; for refreshDeparting to take.
    private var unstoredPair: Pair<String, RefreshOutcome.Refreshed>? = null

    // Under signInLock. How many sends of a refresh token in a row got no reply.
    private var unanswered: Pair<String, Int>? = null

    // Under signInLock. The session refresh on its way, by the token it sends.
    private var refreshOnItsWay: Pair<String, CompletableDeferred<Unit>>? = null

    private fun takeUnstoredPair(token: String): RefreshOutcome.Refreshed? = synchronized(signInLock) {
        unstoredPair?.takeIf { it.first == token }?.second?.also { unstoredPair = null }
    }

    private fun sendsWithoutReply(token: String): Int = synchronized(signInLock) {
        unanswered?.takeIf { it.first == token }?.second ?: 0
    }

    /**
     * Signs in to the backend and stores the pair it returns. When [logout]
     * has moved [signInGeneration] past the value passed here, the reply
     * stores nothing and this throws [AuthException].
     *
     * The backend proves the account with [moodleToken] alone and ignores a
     * password (https://github.com/tigerduck-app/tigerduck-backend/pull/64),
     * so none is sent. Without a token nothing is sent either: such a login
     * could only be refused, and would count against the student's limit.
     */
    suspend fun login(
        studentId: String,
        moodleToken: String,
        moodlePrivateToken: String?,
        platform: String = "android",
        signInGeneration: Int = this.signInGeneration,
    ): LoginResult = signingIn(studentId) {
        if (moodleToken.isBlank()) throw AuthException("no Moodle token")
        val json = JSONObject().apply {
            put("student_id", studentId)
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
        val issuer = baseUrl
        val request = Request.Builder()
            .url("$issuer/auth/login")
            .post(body)
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw AuthException("login failed: ${response.code}")
            val result = JSONObject(response.body.string())
            val accessToken = result.getString("access_token")
            val refreshToken = result.getString("refresh_token")
            val expiresIn = result.getInt("expires_in")
            val expiresAt = now() + expiresIn * 1000L
            if (!saveIfSignedIn(signInGeneration, accessToken, refreshToken, expiresAt, issuer, studentId)) {
                throw AuthException("signed out during login")
            }
            LoginResult(
                accessToken = accessToken,
                deviceId = result.getString("device_id"),
            )
        }
    }

    /**
     * A backend login, on the I/O dispatcher and under the lock of the
     * unregister a sign-out owes, which a login that lands ends: see
     * [OwedUnregister.signingIn].
     */
    private suspend fun <T> signingIn(studentId: String, login: suspend CoroutineScope.() -> T): T =
        withContext(Dispatchers.IO) {
            val owed = owedUnregister ?: return@withContext login()
            owed.signingIn(studentId) { login() }
        }

    /**
     * Drops the pair, and keeps a login or refresh reply still on its way from
     * storing one. The next account's automatic sign-in then goes at once.
     *
     * Hands back the session it drops, for the sign-out's unregister: taken
     * under the same lock, as the generation moves, so it is the pair a refresh
     * landing just before stored, and nothing lands after.
     */
    fun logout(): DepartingSession? {
        var departing: DepartingSession? = null
        synchronized(signInLock) {
            departing = departingSession()
            generation++
            tokens.clear()
            signInUnderWay = null
            signInFailedAt = null
        }
        return departing
    }

    /** Stores the pair unless [logout] has run since [signIn] was read, and says whether it did. */
    private fun saveIfSignedIn(
        signIn: Int,
        access: String,
        refresh: String,
        expiresAt: Long,
        issuedBy: String? = null,
        studentId: String? = null,
    ): Boolean {
        synchronized(signInLock) {
            if (generation != signIn) return false
            tokens.save(access, refresh, expiresAt, issuedBy, studentId)
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
        // Read first: after a sign-out while this waits for a sign-in or a
        // refresh, the session stored is the next account's, not this request's.
        val signIn = signInGeneration
        if (!signInIfSignedOut()) return null
        return validTokenIfSignedIn(signIn) ?: refresh(signIn)
    }

    // The stored access token while it has more than 30 s left and is still
    // generation [signIn]'s.
    private fun validTokenIfSignedIn(signIn: Int): String? = synchronized(signInLock) {
        val access = tokens.accessToken
        if (generation != signIn || access == null || now() >= tokens.expiresAt - 30_000) null else access
    }

    private suspend fun refresh(signIn: Int): String? = refreshMutex.withLock {
        // Double-check after acquiring lock
        validTokenIfSignedIn(signIn)?.let { return it }

        // With the generation under the lock a sign-out takes, so the token is
        // the account's that asked.
        val refreshToken = synchronized(signInLock) {
            if (generation == signIn) tokens.refreshToken else null
        } ?: return null
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
        if (signInAutomatically()) return accessTokenIfSignedIn(signIn)
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
        val onItsWay = refreshToken to CompletableDeferred<Unit>()
        synchronized(signInLock) { refreshOnItsWay = onItsWay }
        try {
            val first = send(refreshToken, signIn)
            if (first != RefreshOutcome.NoReply || signIn != signInGeneration) return first
            return send(refreshToken, signIn) as? RefreshOutcome.Refreshed ?: RefreshOutcome.Refused
        } finally {
            synchronized(signInLock) { if (refreshOnItsWay === onItsWay) refreshOnItsWay = null }
            onItsWay.second.complete(Unit)
        }
    }

    private fun send(refreshToken: String, signIn: Int, base: String = baseUrl): RefreshOutcome {
        val json = JSONObject().apply {
            put("refresh_token", refreshToken)
        }
        val body = json.toString().toRequestBody(jsonType)
        val request = Request.Builder()
            .url("$base/auth/refresh")
            .post(body)
            .build()
        return try {
            refreshClient.newCall(request).execute().use { response ->
                // Answered: whatever the token's sends before got, this one has a reply.
                synchronized(signInLock) { if (unanswered?.first == refreshToken) unanswered = null }
                if (response.code == 429 || response.code >= 500) {
                    Log.w(TAG, "refresh got HTTP ${response.code}; keeping the tokens")
                    return@use RefreshOutcome.Unavailable
                }
                if (!response.isSuccessful) return@use RefreshOutcome.Refused
                val result = JSONObject(response.body.string())
                val newAccess = result.getString("access_token")
                val newRefresh = result.getString("refresh_token")
                val expiresAt = now() + result.getInt("expires_in") * 1000L
                val refreshed = RefreshOutcome.Refreshed(newAccess, newRefresh, expiresAt)
                // Stores nothing after a sign-out, which refreshAndSettle then sees.
                // The pair is kept aside instead, for the sign-out's unregister.
                if (!saveIfSignedIn(signIn, newAccess, newRefresh, expiresAt) && signIn != DEPARTING) {
                    synchronized(signInLock) { unstoredPair = refreshToken to refreshed }
                }
                refreshed
            }
        } catch (e: IOException) {
            if (e.wasNeverSent()) {
                Log.w(TAG, "refresh not sent: ${e.logSummary()}; keeping the tokens")
                RefreshOutcome.Unavailable
            } else {
                Log.w(TAG, "refresh got no reply: ${e.javaClass.simpleName}")
                synchronized(signInLock) {
                    val count = unanswered?.takeIf { it.first == refreshToken }?.second ?: 0
                    unanswered = refreshToken to count + 1
                }
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
        class Refreshed(val accessToken: String, val refreshToken: String, val expiresAt: Long) : RefreshOutcome

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

    /** What a sign-in did: [signInWithStoredAccount], or the one [signInInteractively] runs. */
    enum class SignInAttempt {
        /** It stored a pair for the generation it was given. */
        SIGNED_IN,

        /** It had credentials to send, to the backend or to harvest a Moodle token, and stored no pair. */
        FAILED,

        /** It had nothing to send: no account, the demo account, or neither a password nor a Moodle token. */
        NOTHING_TO_SEND,
    }

    data class LoginResult(val accessToken: String, val deviceId: String)
    class AuthException(message: String) : Exception(message)

    private companion object {
        const val TAG = "AuthTokenManager"
        const val REFRESH_TIMEOUT_SECONDS = 20L

        // No generation is ever this, so a departing refresh stores nothing.
        const val DEPARTING = -1

        // The backend limits refreshes per IP over a minute.
        const val REFRESH_PAUSE_MS = 60_000L

        const val SIGN_IN_PAUSE_MS = 5 * 60_000L
    }
}
