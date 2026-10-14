package org.ntust.app.tigerduck.shared

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resumeWithException

/**
 * NTUST library passport + virtual-code client. Shared between phone (`:app`)
 * and watch (`:wear`) so the wire schema lives in exactly one place. The
 * backing credential store is provided per-platform: phone uses
 * EncryptedSharedPreferences, watch uses a DataStore mirrored from the phone
 * via the Wearable Data Layer.
 */
class LibraryService(
    private val credentials: LibraryCredentialStore,
    /** True only in debug builds — gates the OkHttp body logging. */
    private val isDebugBuild: Boolean = false,
    /**
     * Interceptors the host app wants on this client. It builds its own
     * rather than taking one from DI so the watch can construct it with no
     * Hilt graph, which also puts it outside anything the phone installs
     * globally — the phone passes its demo-mode kill switch through here.
     */
    extraInterceptors: List<Interceptor> = emptyList(),
    /** Wall-clock ms, for the pause after a failed sign-in with the stored credentials. */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val loggingInterceptor = HttpLoggingInterceptor { message ->
        Log.d("TigerDuck-HTTP", LibraryApi.redactSensitive(message))
    }.apply {
        level = if (isDebugBuild) HttpLoggingInterceptor.Level.HEADERS
        else HttpLoggingInterceptor.Level.NONE
        redactHeader("Authorization")
        redactHeader("Cookie")
    }

    private val client = OkHttpClient.Builder()
        .apply { extraInterceptors.forEach(::addInterceptor) }
        .addInterceptor(loggingInterceptor)
        .build()
    private val gson = Gson()
    private val tokenMutex = Mutex()

    /** What became of a sign-in made with the stored credentials, for the app to act on. */
    enum class StoredSignIn {
        /** A new token is stored. */
        SIGNED_IN,

        /** The library refused the stored password; nothing signs in with it again by itself. */
        REFUSED,
    }

    private val _storedSignIns = MutableSharedFlow<StoredSignIn>(extraBufferCapacity = 8)

    /** Each sign-in [ensureToken] makes with the stored credentials that stores a token or is refused. */
    val storedSignIns: SharedFlow<StoredSignIn> = _storedSignIns.asSharedFlow()

    /**
     * When a sign-in with the stored credentials last failed short of a
     * refusal, with no answer or a server error. [signInIfCredentialed] waits
     * [SIGN_IN_PAUSE_MS] after it; opening the Library page does not.
     */
    @Volatile
    private var storedSignInFailedAt: Long? = null

    /** Whether [ensureToken] can sign in again by itself once the token runs out. */
    val canSignInWithStoredCredentials: Boolean
        get() = credentials.libraryUsername != null &&
            credentials.libraryPassword != null &&
            !credentials.libraryPasswordRefused

    /**
     * Whether a library account is signed in, as the Library page, Settings and
     * the flip count it: its token is live, or its stored credentials sign in
     * again behind the code. Only a refused stored password, or no account,
     * signs it out.
     */
    val hasAccount: Boolean
        get() = credentials.isLibraryTokenValid || canSignInWithStoredCredentials

    /**
     * Signs in with the stored credentials when there is no live token, so the
     * Library page shows its code at once: at launch and on each return to the
     * app. Not after a refusal, not within [SIGN_IN_PAUSE_MS] of another
     * failure, and not offline. Returns whether it signed in.
     */
    suspend fun signInIfCredentialed(isOnline: () -> Boolean): Boolean {
        if (credentials.isLibraryTokenValid || !canSignInWithStoredCredentials) return false
        if (!signInIsDue(storedSignInFailedAt, clock()) || !isOnline()) return false
        return try {
            ensureToken()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Signs in and stores the account and its token. Once the library account
     * has signed out since [accountGeneration] was read, the answer is the
     * departing account's: it stores nothing, and this throws
     * [LibraryServiceError.CredentialsNotFound]. A sign-in the app makes on its
     * own ([byUser] false) also yields to another account stored meanwhile; one
     * the user asked for replaces it. The caller reads the generation from the
     * store when its sign-in starts: right before this call, or earlier, as the
     * school sign-in does before its SSO step. [stillCurrent] is asked as the
     * answer is stored: false stores nothing either, for a caller whose own
     * account, such as the school one it signs in with, changed meanwhile.
     */
    suspend fun login(
        username: String,
        password: String,
        accountGeneration: Int,
        byUser: Boolean,
        stillCurrent: () -> Boolean = { true },
    ): String {
        // A sign-in with the stored credentials of an account that has gone
        // since, which no one waits for, would hold this one behind it.
        synchronized(pendingSignIns) { dropAbandonedSignIns() }
        return tokenMutex.withLock {
            loginInternal(username, password, accountGeneration, byUser, stillCurrent)
        }
    }

    private suspend fun loginInternal(
        username: String,
        password: String,
        accountGeneration: Int,
        byUser: Boolean,
        stillCurrent: () -> Boolean = { true },
    ): String =
        withContext(Dispatchers.IO) {
            val body = gson.toJson(LibraryLoginRequest(username, password))
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${LibraryApi.BASE_URL}${LibraryApi.PATH_LOGIN}")
                .post(body)
                .build()

            send(request).use { response ->
                val answer = loginAnswer(response.code, response.body.string())
                val stored = credentials.saveLibrarySignIn(
                    accountGeneration,
                    username,
                    password,
                    answer.token,
                    answer.expirationTimeStamp,
                    byUser,
                    stillCurrent,
                )
                if (!stored) throw LibraryServiceError.CredentialsNotFound()
                answer.token
            }
        }

    /**
     * Reads the library's answer to a sign-in. A 4xx is a refusal like a wrong
     * password: which status a wrong password gets is unchecked, and a refusal
     * stops the automatic sign-ins. A server error or an answer that cannot be
     * read is not.
     */
    private fun loginAnswer(status: Int, body: String): LibraryLoginData {
        val parsed = runCatching { gson.fromJson(body, LibraryLoginResponse::class.java) }.getOrNull()
        val message = serverErrorMessage(status, parsed?.error)
        if (status in 400..499) throw LibraryServiceError.LoginFailed(message)
        if (status !in 200..299 || parsed == null) throw LibraryServiceError.LoginUnavailable(message)
        if (parsed.error?.code?.let { it != 0 } == true) throw LibraryServiceError.LoginFailed(message)
        return parsed.data ?: throw LibraryServiceError.LoginFailed(message)
    }

    /**
     * Returns a valid token, signing in again with the stored credentials once
     * it runs out. Callers that ask while that sign-in is out for the account
     * wait for it and share its outcome, rather than each sending the password.
     */
    suspend fun ensureToken(): String {
        val token = credentials.libraryToken
        if (token != null && credentials.isLibraryTokenValid) return token
        val username = credentials.libraryUsername
            ?: throw LibraryServiceError.CredentialsNotFound()
        if (credentials.libraryPassword == null) throw LibraryServiceError.CredentialsNotFound()
        // Each refusal counts toward the account's lockout, so a refused
        // password is not sent again until a sign-in saves credentials that work.
        if (credentials.libraryPasswordRefused) throw LibraryServiceError.CredentialsNotFound()

        val attempt = synchronized(pendingSignIns) {
            dropAbandonedSignIns()
            // Read under the lock, so no attempt is cancelled for an older
            // generation after this read it and before this joins.
            val key = SignInKey(username, credentials.libraryAccountGeneration)
            // One cancelled as abandoned but not yet done is not joined: its
            // caller would get a cancellation it did not ask for. One that
            // failed and is not yet removed is, and shares its outcome rather
            // than sending the password again.
            (pendingSignIns[key]?.takeUnless { it.abandoned } ?: startSignIn(key)).also { it.waiting++ }
        }
        try {
            return attempt.job.await()
        } finally {
            synchronized(pendingSignIns) {
                attempt.waiting--
                dropAbandonedSignIns()
            }
        }
    }

    /** The account a sign-in with the stored credentials is for, as it stood when asked. */
    private data class SignInKey(val username: String, val generation: Int)

    private class SignInAttempt(val key: SignInKey) {
        lateinit var job: Deferred<String>

        /** The callers waiting for it; guarded by [pendingSignIns]. */
        var waiting = 0

        /**
         * The generation it signs in at, once it has the lock: the account
         * stored by then, which can be one stored after it was asked for.
         */
        @Volatile
        var signingInAt: Int? = null

        /** The generation its answer is for. */
        val generation: Int get() = signingInAt ?: key.generation

        /** Cancelled because no one waits for it and its account has gone; guarded by [pendingSignIns]. */
        var abandoned = false
    }

    /**
     * Each account's sign-in with the stored credentials that is under way.
     * It runs in [signInScope], so a caller that stops waiting leaves it to
     * finish, and its outcome is kept.
     */
    private val pendingSignIns = HashMap<SignInKey, SignInAttempt>()
    private val signInScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Under the [pendingSignIns] lock. */
    private fun startSignIn(key: SignInKey): SignInAttempt {
        val attempt = SignInAttempt(key)
        attempt.job = signInScope.async(start = CoroutineStart.LAZY) {
            storedSignIn { attempt.signingInAt = it }
        }
        pendingSignIns[key] = attempt
        attempt.job.invokeOnCompletion {
            synchronized(pendingSignIns) {
                if (pendingSignIns[key] === attempt) pendingSignIns.remove(key)
            }
        }
        attempt.job.start()
        return attempt
    }

    /**
     * Cancels each sign-in no caller waits for whose account has signed out or
     * been replaced since: its answer would store nothing, and it would hold
     * the next account's sign-in behind it until the library answered or timed
     * out, as on the watch when the phone hands over another account. One
     * still waited for runs on, and so does one signing in the account stored
     * now. Under the [pendingSignIns] lock.
     */
    private fun dropAbandonedSignIns() {
        val generation = credentials.libraryAccountGeneration
        pendingSignIns.values
            .filter { it.waiting == 0 && it.generation != generation }
            .forEach {
                it.abandoned = true
                it.job.cancel()
            }
    }

    private suspend fun storedSignIn(signingInAt: (Int) -> Unit): String = tokenMutex.withLock {
        // Before the account it signs in with, so a sign-out after this keeps
        // the answer from being stored.
        val accountGeneration = credentials.libraryAccountGeneration
        signingInAt(accountGeneration)
        // A sign-in that landed while this waited for the lock, typed or not.
        val token = credentials.libraryToken
        if (token != null && credentials.isLibraryTokenValid) return@withLock token
        val username = credentials.libraryUsername
            ?: throw LibraryServiceError.CredentialsNotFound()
        val password = credentials.libraryPassword
            ?: throw LibraryServiceError.CredentialsNotFound()
        if (credentials.libraryPasswordRefused) throw LibraryServiceError.CredentialsNotFound()

        val outcome = try {
            Result.success(loginInternal(username, password, accountGeneration, byUser = false))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        // Kept whatever happens to the callers: a refusal that went unrecorded
        // would let the password be sent again.
        withContext(NonCancellable) {
            val failure = outcome.exceptionOrNull()
            when {
                failure == null -> {
                    storedSignInFailedAt = null
                    _storedSignIns.tryEmit(StoredSignIn.SIGNED_IN)
                }
                // A failure says something only about the credentials still stored.
                failure is LibraryServiceError.LoginFailed -> {
                    if (credentials.refuseLibraryPassword(accountGeneration, password)) {
                        _storedSignIns.tryEmit(StoredSignIn.REFUSED)
                    }
                }
                credentials.libraryAccountGeneration == accountGeneration &&
                    credentials.libraryPassword == password -> storedSignInFailedAt = clock()
            }
        }
        outcome.getOrThrow()
    }

    suspend fun generateQRCode(): String = withContext(Dispatchers.IO) {
        val token = ensureToken()
        val body = gson.toJson(LibraryQRRequest(token))
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("${LibraryApi.BASE_URL}${LibraryApi.PATH_GENERATE_QR}")
            .post(body)
            .build()

        send(request).use { response ->
            val responseBody = response.body.string()
            val qrResponse = runCatching {
                gson.fromJson(responseBody, LibraryQRResponse::class.java)
            }.getOrNull()

            if (qrResponse?.data == null ||
                qrResponse.error?.code?.let { it != 0 } == true
            ) {
                throw LibraryServiceError.QRGenerationFailed(
                    serverErrorMessage(response.code, qrResponse?.error)
                )
            }
            qrResponse.data
        }
    }

    /**
     * Sends [request] and waits for its response, cancelling the call when the caller is
     * cancelled. A blocking execute() went on until the library answered or timed out, and held
     * a caller that had already moved on, such as the watch's page after the phone handed over
     * another account.
     */
    private suspend fun send(request: Request): Response = suspendCancellableCoroutine { waiting ->
        val call = client.newCall(request)
        waiting.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = waiting.resumeWithException(e)

            override fun onResponse(call: Call, response: Response) =
                waiting.resume(response) { _, unused, _ -> unused.close() }
        })
    }

    /**
     * Build a human-readable error message that never collapses to blank. The
     * library API sometimes responds with `error.message = ""` (e.g. on a wrong
     * password), and Elvis `?:` alone passes that empty string through — which
     * in turn renders as a red box with no text in the UI. Treat blank as
     * missing, and tack on the HTTP status when we have nothing better.
     */
    private fun serverErrorMessage(httpStatus: Int, error: LibraryApiError?): String {
        val msg = error?.message?.takeUnless { it.isBlank() }
        if (msg != null) return msg
        return if (httpStatus in 200..299) GENERIC_ERROR else "$GENERIC_ERROR (HTTP $httpStatus)"
    }

    companion object {
        // Localized error strings live in the UI layer of each module; the
        // shared service falls back to a stable non-empty marker so callers
        // can still display *something* if the server omits a message.
        private const val GENERIC_ERROR = "Library request failed"

        /** How long a return to the app waits after a sign-in that failed short of a refusal. */
        const val SIGN_IN_PAUSE_MS = 5 * 60 * 1000L

        /**
         * Whether a return to the app may sign in again. Measured from the
         * failure, so a clock set back does not stretch the pause.
         */
        fun signInIsDue(lastFailure: Long?, now: Long): Boolean {
            if (lastFailure == null) return true
            val age = now - lastFailure
            return age >= SIGN_IN_PAUSE_MS || age < 0
        }
    }
}
