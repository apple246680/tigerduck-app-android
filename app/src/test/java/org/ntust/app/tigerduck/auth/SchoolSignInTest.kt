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
 * succeeds. Only a typed sign-in has that step: signing in again with the
 * stored school account leaves the library to its own stored credentials
 * (tigerduck-app/tigerduck-app-android#218).
 */
class SchoolSignInTest {

    private class MemoryLibrary : LibraryCredentialStore {
        private val account = LibraryAccountGate()
        override var libraryUsername: String? = null
        override var libraryPassword: String? = null
        override var libraryToken: String? = null
        override var libraryTokenExpiry: Long = 0L
        override var libraryPasswordRefused: Boolean = false
        override val isLibraryTokenValid: Boolean
            get() = libraryToken != null && System.currentTimeMillis() < libraryTokenExpiry
        /** Runs as the library account generation is read. */
        var onGenerationRead: () -> Unit = {}

        override val libraryAccountGeneration: Int
            get() {
                onGenerationRead()
                return account.current
            }

        override fun saveLibrarySignIn(
            generation: Int,
            username: String,
            password: String,
            token: String?,
            tokenExpiry: Long,
            byUser: Boolean,
            stillCurrent: () -> Boolean,
        ): Boolean = account.saveIfCurrent(generation, byUser, { libraryUsername }, username, stillCurrent) {
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

    /** Moved by each school sign-out, as the credential store's is. */
    private var schoolAccount = 0
    private var storedId: String? = "B10000001"
    private var storedPassword: String? = "pw"
    private var warm = false
    private var schoolSignIns = 0
    private var librarySignIns = 0

    /** Runs while the SSO step is out. */
    private var duringSchoolSignIn: () -> Unit = {}

    /** Runs while the library is answering, before the answer reaches the app. */
    private var duringLibrarySignIn: () -> Unit = {}

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
        schoolAccount = { schoolAccount },
        isSchoolAccount = { it == schoolAccount },
        studentId = { storedId },
        password = { storedPassword },
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
    fun `a typed sign-in signs in to the library with the same account`() = runTest {
        assertTrue(signIn.typed("B10000001", "pw"))
        assertEquals("B10000001", library.libraryUsername)
        assertEquals("token", library.libraryToken)
    }

    @Test
    fun `a sign-in again leaves the library alone`() = runTest {
        assertTrue(signIn.stored())

        assertEquals(1, schoolSignIns)
        assertEquals(0, librarySignIns)
        assertNoLibraryAccount()
    }

    /** It used to sign a user who had signed out of the library back in. */
    @Test
    fun `a library account signed out of stays signed out through a sign-in again`() = runTest {
        assertTrue(signIn.typed("B10000001", "pw"))
        library.signOut()

        assertTrue(signIn.stored())

        assertEquals(1, librarySignIns)
        assertNoLibraryAccount()
    }

    @Test
    fun `a sign-out during the SSO step of a typed sign-in keeps the library step from storing it`() = runTest {
        duringSchoolSignIn = { library.signOut() }

        assertTrue(signIn.typed("B10000002", "pw2"))

        assertNoLibraryAccount()
    }

    /**
     * A school sign-out keeps a library account of another student, so it
     * moves no library generation; the step still stores nothing for the
     * student who left.
     */
    @Test
    fun `a school sign-out while the library answers keeps the library step from storing it`() = runTest {
        duringLibrarySignIn = { schoolAccount++ }

        assertTrue(signIn.typed("B10000002", "pw2"))

        assertNoLibraryAccount()
    }

    /**
     * The school account is read before the library generation: a school sign-out between the
     * two reads, which keeps another student's library account and so moves no generation,
     * still keeps the step from storing anything.
     */
    @Test
    fun `a school sign-out as the sign-in begins keeps the library step from storing it`() = runTest {
        library.onGenerationRead = {
            library.onGenerationRead = {}
            schoolAccount++
        }

        assertTrue(signIn.typed("B10000002", "pw2"))

        assertNoLibraryAccount()
    }

    /** The library step is the app's own sign-in, so the user's choice made meanwhile stands. */
    @Test
    fun `a library account the user signed in to meanwhile is kept`() = runTest {
        duringLibrarySignIn = {
            assertTrue(library.saveLibrarySignIn(library.libraryAccountGeneration, "B10000009", "theirs", "user", Long.MAX_VALUE, byUser = true))
        }

        assertTrue(signIn.typed("B10000001", "pw"))

        assertEquals("B10000009", library.libraryUsername)
        assertEquals("user", library.libraryToken)
    }

    @Test
    fun `a library token still valid is kept, and a warm session skips the SSO step`() = runTest {
        library.saveLibrarySignIn(library.libraryAccountGeneration, "B10000001", "pw", "kept", Long.MAX_VALUE, byUser = true)
        assertTrue(signIn.typed("B10000001", "pw"))
        assertEquals("kept", library.libraryToken)
        assertEquals(1, schoolSignIns)
        assertEquals(0, librarySignIns)

        warm = true
        assertTrue(signIn.stored())
        assertEquals(1, schoolSignIns)
    }

    /**
     * As on the iPhone (tigerduck-app/tigerduck-app#542): a library account of another student
     * ID that can still sign in by itself stays, as a school sign-out keeps it, though its
     * day-long token has run out.
     */
    @Test
    fun `another student's library account that can still sign in is kept`() = runTest {
        library.saveLibrarySignIn(library.libraryAccountGeneration, "B10000009", "theirs", "expired", 1L, byUser = true)

        assertTrue(signIn.typed("b10000001", "pw"))

        assertEquals(0, librarySignIns)
        assertEquals("B10000009", library.libraryUsername)
        assertEquals("theirs", library.libraryPassword)
    }

    @Test
    fun `another student's library account the library refused gives way to the school account's`() = runTest {
        library.saveLibrarySignIn(library.libraryAccountGeneration, "B10000009", "theirs", "expired", 1L, byUser = true)
        library.libraryPasswordRefused = true

        assertTrue(signIn.typed("B10000001", "pw"))

        assertEquals(1, librarySignIns)
        assertEquals("B10000001", library.libraryUsername)
        assertEquals("token", library.libraryToken)
    }

    /** The student's own library account takes the school credentials once its token has run out, as before. */
    @Test
    fun `the same student's library account whose token has run out signs in with the school credentials`() = runTest {
        library.saveLibrarySignIn(library.libraryAccountGeneration, "b10000001", "library", "expired", 1L, byUser = true)

        assertTrue(signIn.typed("B10000001", "pw"))

        assertEquals(1, librarySignIns)
        assertEquals("B10000001", library.libraryUsername)
        assertEquals("pw", library.libraryPassword)
        assertEquals("token", library.libraryToken)
    }

    /** A user name stored before the library form trimmed it can carry spaces around it. */
    @Test
    fun `the same student's library account is not kept as another's though its stored name carries spaces`() = runTest {
        library.saveLibrarySignIn(library.libraryAccountGeneration, " b11234567 ", "library", "expired", 1L, byUser = true)

        assertTrue(signIn.typed("B11234567", "pw"))

        assertEquals(1, librarySignIns)
        assertEquals("B11234567", library.libraryUsername)
        assertEquals("token", library.libraryToken)
    }

    @Test
    fun `no stored account signs nothing in`() = runTest {
        storedId = null
        assertFalse(signIn.stored())
        assertEquals(0, schoolSignIns)
    }
}
