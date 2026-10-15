package org.ntust.app.tigerduck.auth

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.shared.LibraryAccountGate
import org.ntust.app.tigerduck.shared.LibraryApi
import org.ntust.app.tigerduck.shared.LibraryCredentialStore
import org.ntust.app.tigerduck.shared.LibraryService
import org.ntust.app.tigerduck.shared.sameStudentId

/**
 * The library sign-in after the school's typed one, and a sign-out that lands while it runs: it
 * stores nothing for the student who left, however early the sign-out came
 * (tigerduck-app/tigerduck-app-android#293), and it keeps another student's library account that
 * can still sign in, as a school sign-out does (tigerduck-app/tigerduck-app#542).
 */
class LibraryAfterSchoolSignInTest {

    private class MemoryLibrary : LibraryCredentialStore {
        private val account = LibraryAccountGate()

        // Moved by each school sign-out, under the library account's lock, as the credential
        // store's is.
        private var schoolAccount = 0

        override var libraryUsername: String? = null
        override var libraryPassword: String? = null
        override var libraryToken: String? = null
        override var libraryTokenExpiry: Long = 0L
        override var libraryPasswordRefused: Boolean = false
        override val isLibraryTokenValid: Boolean
            get() = libraryToken != null && System.currentTimeMillis() < libraryTokenExpiry
        override val libraryAccountGeneration: Int get() = account.current

        val schoolAccountGeneration: Int get() = account.atomically { schoolAccount }

        fun isSchoolAccount(read: Int): Boolean = account.atomically { schoolAccount == read }

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

        /** What CredentialManager.clearLibraryCredentials(ifSchoolAccount) does. */
        fun signOutSchool(departing: String?) = account.atomically {
            schoolAccount++
            if (departing != null && sameStudentId(libraryUsername, departing)) signOut()
        }
    }

    private val library = MemoryLibrary()

    /** The sign-in generation a sign-out moves, as AuthTokenManager's. */
    private var generation = 0
    private val accountLock = Any()
    private var schoolStudent: String? = STUDENT
    private var librarySignIns = 0

    /** Runs while the library is answering, before the answer reaches the app. */
    private var duringLibrarySignIn: () -> Unit = {}

    /** What AuthService.logout does to the school account, the library's and the generation. */
    private fun signOut() = synchronized(accountLock) {
        library.signOutSchool(schoolStudent)
        schoolStudent = null
        generation++
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
                """{"data":{"username":"$STUDENT","token":"token","expirationTimeStamp":${Long.MAX_VALUE}}}"""
                    .toResponseBody("application/json".toMediaType()),
            )
            .build()
    }

    private val libraryService = LibraryService(library, extraInterceptors = listOf(libraryServer))

    private fun librarySignIn() = LibraryAfterSchoolSignIn(
        tokenValid = { library.isLibraryTokenValid },
        hasAccountOtherThan = libraryService::hasAccountOtherThan,
        schoolAccount = { library.schoolAccountGeneration },
        isSchoolAccount = library::isSchoolAccount,
        libraryAccount = { library.libraryAccountGeneration },
        login = { studentId, password, libraryAccount, stillCurrent ->
            libraryService.login(studentId, password, libraryAccount, byUser = false, stillCurrent)
        },
        signInGeneration = { generation },
        accountLock = accountLock,
    )

    /** The sign-in as AuthService makes it: a dropped answer only logs there. */
    private suspend fun signIn(studentId: String = STUDENT, password: String = "pw", from: Int = generation) {
        runCatching { librarySignIn().signIn(studentId, password, from) }
    }

    private fun assertNoLibraryAccount() {
        assertNull(library.libraryUsername)
        assertNull(library.libraryPassword)
        assertNull(library.libraryToken)
    }

    @Test
    fun `the library signs in with the account and keeps what it got`() = runTest {
        signIn()

        assertEquals(STUDENT, library.libraryUsername)
        assertEquals("pw", library.libraryPassword)
        assertEquals("token", library.libraryToken)
    }

    @Test
    fun `a library token still good signs in nothing`() = runTest {
        assertTrue(library.saveLibrarySignIn(library.libraryAccountGeneration, STUDENT, "pw", "kept", Long.MAX_VALUE, byUser = true))

        signIn()

        assertEquals(0, librarySignIns)
        assertEquals("kept", library.libraryToken)
    }

    @Test
    fun `a sign-out before the library step signs nothing in`() = runTest {
        val before = generation
        signOut()

        signIn(from = before)

        assertEquals(0, librarySignIns)
        assertNoLibraryAccount()
    }

    /**
     * The library account is not the departing student's yet, so the sign-out moves no library
     * generation; the school account's keeps the answer from being stored.
     */
    @Test
    fun `a sign-out while the library answers leaves none of its credentials`() = runTest {
        duringLibrarySignIn = { signOut() }

        signIn()

        assertNoLibraryAccount()
    }

    @Test
    fun `a library sign-out while the library answers keeps it from storing`() = runTest {
        duringLibrarySignIn = { library.signOut() }

        signIn()

        assertNoLibraryAccount()
    }

    /** The library step is the app's own sign-in, so the user's choice made meanwhile stands. */
    @Test
    fun `a library account the user signed in to meanwhile is kept`() = runTest {
        duringLibrarySignIn = {
            assertTrue(library.saveLibrarySignIn(library.libraryAccountGeneration, OTHER, "theirs", "user", Long.MAX_VALUE, byUser = true))
        }

        signIn()

        assertEquals(OTHER, library.libraryUsername)
        assertEquals("user", library.libraryToken)
    }

    /** It stays though its day-long token has run out, as a school sign-out keeps it. */
    @Test
    fun `another student's library account that can still sign in is kept`() = runTest {
        library.saveLibrarySignIn(library.libraryAccountGeneration, OTHER, "theirs", "expired", 1L, byUser = true)

        signIn(studentId = STUDENT.lowercase())

        assertEquals(0, librarySignIns)
        assertEquals(OTHER, library.libraryUsername)
        assertEquals("theirs", library.libraryPassword)
    }

    @Test
    fun `another student's library account the library refused gives way to the school account's`() = runTest {
        library.saveLibrarySignIn(library.libraryAccountGeneration, OTHER, "theirs", "expired", 1L, byUser = true)
        library.libraryPasswordRefused = true

        signIn()

        assertEquals(1, librarySignIns)
        assertEquals(STUDENT, library.libraryUsername)
        assertEquals("token", library.libraryToken)
    }

    /** The student's own library account takes the school credentials once its token has run out. */
    @Test
    fun `the same student's library account whose token has run out signs in with the school credentials`() = runTest {
        library.saveLibrarySignIn(library.libraryAccountGeneration, STUDENT.lowercase(), "library", "expired", 1L, byUser = true)

        signIn()

        assertEquals(1, librarySignIns)
        assertEquals(STUDENT, library.libraryUsername)
        assertEquals("pw", library.libraryPassword)
        assertEquals("token", library.libraryToken)
    }

    /** A user name stored before the library form trimmed it can carry spaces around it. */
    @Test
    fun `the same student's library account is not kept as another's though its stored name carries spaces`() = runTest {
        library.saveLibrarySignIn(library.libraryAccountGeneration, " ${STUDENT.lowercase()} ", "library", "expired", 1L, byUser = true)

        signIn()

        assertEquals(1, librarySignIns)
        assertEquals(STUDENT, library.libraryUsername)
        assertEquals("token", library.libraryToken)
    }

    private companion object {
        const val STUDENT = "B10000001"
        const val OTHER = "B10000009"
    }
}
