package org.ntust.app.tigerduck.auth

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.LibraryAccountGate
import org.ntust.app.tigerduck.shared.LibraryApi
import org.ntust.app.tigerduck.shared.LibraryCredentialStore
import org.ntust.app.tigerduck.shared.LibraryService

/**
 * The school sign-in's library step stores nothing for an account that signed
 * out while it ran, however early the sign-out came: a sign-out takes the
 * library account too, and the step's answer is the departing student's
 * (tigerduck-app/tigerduck-app-android#293). The school sign-in itself still
 * succeeds.
 */
class SchoolSignInTest {

    private class MemoryLibrary : LibraryCredentialStore {
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
    }

    private val library = MemoryLibrary()
    private var storedId: String? = "B10000001"
    private var storedPassword: String? = "pw"
    private var warm = false
    private var schoolSignIns = 0
    private var librarySignIns = 0

    /** Runs once the stored account has been read, before the sign-in goes on. */
    private var afterAccountRead: () -> Unit = {}

    /** Runs while the SSO step is out. */
    private var duringSchoolSignIn: () -> Unit = {}

    /** Runs while the library is answering, before the answer reaches the app. */
    private var duringLibrarySignIn: () -> Unit = {}

    // What a sign-out does to the school account and, with it, the library one.
    private fun signOut() {
        storedId = null
        storedPassword = null
        library.signOut()
    }

    private val libraryServer = Interceptor { chain ->
        check(chain.request().url.encodedPath.endsWith(LibraryApi.PATH_LOGIN))
        librarySignIns++
        duringLibrarySignIn()
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(
                """{"data":{"username":"B10000001","token":"token","expirationTimeStamp":${Long.MAX_VALUE}}}"""
                    .toResponseBody("application/json".toMediaType()),
            )
            .build()
    }

    private val signIn = SchoolSignIn(
        libraryStore = library,
        libraryService = LibraryService(library, extraInterceptors = listOf(libraryServer)),
        studentId = { storedId },
        password = { storedPassword.also { afterAccountRead() } },
        sessionIsWarm = { warm },
        signInToSchool = { _, _, _ ->
            schoolSignIns++
            duringSchoolSignIn()
            true
        },
        afterLibrarySignIn = {},
    )

    private fun assertNoLibraryAccount() {
        assertNull(library.libraryUsername)
        assertNull(library.libraryPassword)
        assertNull(library.libraryToken)
    }

    @Test
    fun `a sign-in again signs in to the library with the stored account`() = runTest {
        assertTrue(signIn.stored())
        assertEquals("B10000001", library.libraryUsername)
        assertEquals("token", library.libraryToken)
    }

    @Test
    fun `a sign-out right after the stored account is read keeps the library step from storing it`() = runTest {
        afterAccountRead = { signOut() }

        assertTrue(signIn.stored())

        assertEquals(1, librarySignIns)
        assertNoLibraryAccount()
    }

    @Test
    fun `a sign-out during the SSO step of a sign-in again keeps the library step from storing it`() = runTest {
        duringSchoolSignIn = { signOut() }

        assertTrue(signIn.stored())

        assertNoLibraryAccount()
    }

    @Test
    fun `a sign-out during the SSO step of a typed sign-in keeps the library step from storing it`() = runTest {
        duringSchoolSignIn = { library.signOut() }

        assertTrue(signIn.typed("B10000002", "pw2"))

        assertNoLibraryAccount()
    }

    /** The library step is the app's own sign-in, so the user's choice made meanwhile stands. */
    @Test
    fun `a library account the user signed in to meanwhile is kept`() = runTest {
        duringLibrarySignIn = {
            assertTrue(library.saveLibrarySignIn(library.libraryAccountGeneration, "B10000009", "theirs", "user", Long.MAX_VALUE, byUser = true))
        }

        assertTrue(signIn.stored())

        assertEquals("B10000009", library.libraryUsername)
        assertEquals("user", library.libraryToken)
    }

    @Test
    fun `a library token still valid is kept, and a warm session skips the SSO step`() = runTest {
        library.saveLibrarySignIn(library.libraryAccountGeneration, "B10000001", "pw", "kept", Long.MAX_VALUE, byUser = true)
        assertTrue(signIn.stored())
        assertEquals("kept", library.libraryToken)
        assertEquals(1, schoolSignIns)
        assertEquals(0, librarySignIns)

        warm = true
        assertTrue(signIn.stored())
        assertEquals(1, schoolSignIns)
    }

    @Test
    fun `no stored account signs nothing in`() = runTest {
        storedId = null
        assertFalse(signIn.stored())
        assertEquals(0, schoolSignIns)
    }
}
