package org.ntust.app.tigerduck.shared

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A library sign-in whose account signs out, or is replaced, while its request
 * is out stores nothing: stored, it would sign the next student in to the
 * library as the one who left (tigerduck-app/tigerduck-app#439, #436).
 */
class LibraryServiceAccountTest {

    private class MemoryStore : LibraryCredentialStore {
        private val account = LibraryAccountGate()
        override var libraryUsername: String? = null
        override var libraryPassword: String? = null
        override var libraryToken: String? = null
        override var libraryTokenExpiry: Long = 0L
        override val isLibraryTokenValid: Boolean
            get() = libraryToken != null && System.currentTimeMillis() < libraryTokenExpiry
        override val libraryAccountGeneration: Int get() = account.current

        override fun saveLibrarySignIn(
            generation: Int,
            username: String,
            password: String,
            token: String?,
            tokenExpiry: Long,
            byUser: Boolean,
        ): Boolean = account.saveIfCurrent(generation, byUser, { libraryUsername }, username) {
            libraryUsername = username
            libraryPassword = password
            libraryToken = token
            libraryTokenExpiry = tokenExpiry
        }

        fun signOut() = account.signOut {
            libraryUsername = null
            libraryPassword = null
            libraryToken = null
            libraryTokenExpiry = 0L
        }

        /** An account whose token has run out, so the next code signs in again. */
        fun storeExpired(username: String, password: String) {
            libraryUsername = username
            libraryPassword = password
            libraryToken = "expired"
            libraryTokenExpiry = 1L
        }
    }

    private val store = MemoryStore()

    /** Runs while the library is answering a sign-in, before the answer reaches the app. */
    private var whileSigningIn: () -> Unit = {}
    private var signIns = 0

    private val library = Interceptor { chain ->
        val path = chain.request().url.encodedPath
        val body = when {
            path.endsWith(LibraryApi.PATH_LOGIN) -> {
                signIns++
                whileSigningIn()
                """{"data":{"username":"B10000001","token":"token-$signIns","expirationTimeStamp":${Long.MAX_VALUE}}}"""
            }
            path.endsWith(LibraryApi.PATH_GENERATE_QR) -> """{"data":"code"}"""
            else -> error("unexpected request to $path")
        }
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }

    private val service = LibraryService(store, extraInterceptors = listOf(library))

    @Test
    fun `a sign-in stores the account and its token`() = runBlocking {
        assertEquals("token-1", service.login("B10000001", "pw", store.libraryAccountGeneration, byUser = true))
        assertEquals("B10000001", store.libraryUsername)
        assertEquals("pw", store.libraryPassword)
        assertEquals("token-1", store.libraryToken)
    }

    @Test
    fun `a sign-in answered after a sign-out stores nothing`() = runBlocking {
        whileSigningIn = { store.signOut() }

        val thrown = runCatching { service.login("B10000001", "pw", store.libraryAccountGeneration, byUser = true) }.exceptionOrNull()

        assertTrue("expected CredentialsNotFound, got $thrown", thrown is LibraryServiceError.CredentialsNotFound)
        assertNull(store.libraryUsername)
        assertNull(store.libraryPassword)
        assertNull(store.libraryToken)
    }

    @Test
    fun `a code asked for as a sign-out lands brings back no account`() = runBlocking {
        store.storeExpired("B10000001", "pw")
        whileSigningIn = { store.signOut() }

        val thrown = runCatching { service.generateQRCode() }.exceptionOrNull()

        assertTrue("expected CredentialsNotFound, got $thrown", thrown is LibraryServiceError.CredentialsNotFound)
        assertNull(store.libraryUsername)
        assertNull(store.libraryPassword)
        assertNull(store.libraryToken)
    }

    @Test
    fun `a sign-in begun before a sign-out stores nothing, though its request goes out after it`() = runBlocking {
        // The school sign-in reads the generation before its SSO step, then signs in to the
        // library once that step is done.
        val generation = store.libraryAccountGeneration
        store.signOut()

        val thrown = runCatching { service.login("B10000001", "pw", generation, byUser = false) }.exceptionOrNull()

        assertTrue("expected CredentialsNotFound, got $thrown", thrown is LibraryServiceError.CredentialsNotFound)
        assertNull(store.libraryUsername)
        assertNull(store.libraryToken)
    }

    @Test
    fun `another account stored meanwhile is kept`() = runBlocking {
        store.storeExpired("B10000001", "pw")
        whileSigningIn = {
            assertTrue(store.saveLibrarySignIn(store.libraryAccountGeneration, "B10000002", "other", "theirs", Long.MAX_VALUE, byUser = true))
        }

        runCatching { service.ensureToken() }

        assertEquals("B10000002", store.libraryUsername)
        assertEquals("theirs", store.libraryToken)
    }

    /**
     * A sign-in the user asked for, answered after the app's own sign-in stored another account
     * meanwhile, is what the user wants, and only a sign-out stops it.
     */
    @Test
    fun `a sign-in the user asked for replaces another account stored meanwhile`() = runBlocking {
        whileSigningIn = {
            assertTrue(store.saveLibrarySignIn(store.libraryAccountGeneration, "B10000002", "other", "theirs", Long.MAX_VALUE, byUser = false))
        }

        assertEquals("token-1", service.login("b10000001", "pw", store.libraryAccountGeneration, byUser = true))
        assertEquals("b10000001", store.libraryUsername)
        assertEquals("token-1", store.libraryToken)
    }

    /** The school sign-in upper-cases the ID and the library form keeps what was typed. */
    @Test
    fun `the same student in another case is not another account`() = runBlocking {
        store.storeExpired("B10000001", "pw")
        val generation = store.libraryAccountGeneration

        assertTrue(store.saveLibrarySignIn(generation, "b10000001", "pw", "fresh", Long.MAX_VALUE, byUser = true))
        // A sign-in the app made on its own, begun before that save, still stores its answer.
        assertTrue(store.saveLibrarySignIn(generation, "B10000001", "pw", "own", Long.MAX_VALUE, byUser = false))
        assertEquals("own", store.libraryToken)
    }

    @Test
    fun `a sign-in again for the account still stored keeps its new token`() = runBlocking {
        store.storeExpired("B10000001", "pw")

        assertEquals("token-1", service.ensureToken())
        assertEquals("B10000001", store.libraryUsername)
        assertEquals("token-1", store.libraryToken)
    }
}
