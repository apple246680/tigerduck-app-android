package org.ntust.app.tigerduck.shared

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor

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

    /**
     * Signs in and stores the account and its token. Once the library account
     * has signed out since [accountGeneration] was read, the answer is the
     * departing account's: it stores nothing, and this throws
     * [LibraryServiceError.CredentialsNotFound]. A sign-in the app makes on its
     * own ([byUser] false) also yields to another account stored meanwhile; one
     * the user asked for replaces it. The caller reads the generation from the
     * store when its sign-in starts: right before this call, or earlier, as the
     * school sign-in does before its SSO step.
     */
    suspend fun login(
        username: String,
        password: String,
        accountGeneration: Int,
        byUser: Boolean,
    ): String = tokenMutex.withLock {
        loginInternal(username, password, accountGeneration, byUser)
    }

    private suspend fun loginInternal(
        username: String,
        password: String,
        accountGeneration: Int,
        byUser: Boolean,
    ): String =
        withContext(Dispatchers.IO) {
            val body = gson.toJson(LibraryLoginRequest(username, password))
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${LibraryApi.BASE_URL}${LibraryApi.PATH_LOGIN}")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body.string()
                val loginResponse = runCatching {
                    gson.fromJson(responseBody, LibraryLoginResponse::class.java)
                }.getOrNull()

                if (loginResponse?.data == null ||
                    loginResponse.error?.code?.let { it != 0 } == true
                ) {
                    throw LibraryServiceError.LoginFailed(
                        serverErrorMessage(response.code, loginResponse?.error)
                    )
                }

                val token = loginResponse.data.token
                val stored = credentials.saveLibrarySignIn(
                    accountGeneration,
                    username,
                    password,
                    token,
                    loginResponse.data.expirationTimeStamp,
                    byUser,
                )
                if (!stored) throw LibraryServiceError.CredentialsNotFound()
                token
            }
        }

    suspend fun ensureToken(): String = tokenMutex.withLock {
        // Before the account it signs in with, so a sign-out after this keeps
        // the answer from being stored.
        val accountGeneration = credentials.libraryAccountGeneration
        val token = credentials.libraryToken
        if (token != null && credentials.isLibraryTokenValid) return@withLock token

        val username = credentials.libraryUsername
            ?: throw LibraryServiceError.CredentialsNotFound()
        val password = credentials.libraryPassword
            ?: throw LibraryServiceError.CredentialsNotFound()

        loginInternal(username, password, accountGeneration, byUser = false)
    }

    suspend fun generateQRCode(): String = withContext(Dispatchers.IO) {
        val token = ensureToken()
        val body = gson.toJson(LibraryQRRequest(token))
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("${LibraryApi.BASE_URL}${LibraryApi.PATH_GENERATE_QR}")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
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

    private companion object {
        // Localized error strings live in the UI layer of each module; the
        // shared service falls back to a stable non-empty marker so callers
        // can still display *something* if the server omits a message.
        const val GENERIC_ERROR = "Library request failed"
    }
}
