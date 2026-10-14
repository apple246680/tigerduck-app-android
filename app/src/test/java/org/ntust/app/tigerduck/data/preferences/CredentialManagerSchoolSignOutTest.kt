package org.ntust.app.tigerduck.data.preferences

import android.app.Application
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.library.FakeLibraryServer
import org.ntust.app.tigerduck.shared.LibraryServiceError
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A school sign-out takes the library account of the same student ID, read
 * before the school account is wiped, and leaves one signed in with another
 * ID, as on the iPhone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CredentialManagerSchoolSignOutTest {

    // Plain preferences: Robolectric has no Android keystore for the encrypted ones.
    private val prefs = RuntimeEnvironment.getApplication()
        .getSharedPreferences("credentials-school-sign-out-test", Context.MODE_PRIVATE)
    private lateinit var credentials: CredentialManager

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        credentials = CredentialManager(prefs)
    }

    private fun libraryAccount(username: String) {
        assertTrue(
            credentials.saveLibrarySignIn(credentials.libraryAccountGeneration, username, "pw", "token", Long.MAX_VALUE, byUser = true),
        )
    }

    @Test
    fun `the same student's library account goes with the school account`() {
        credentials.ntustStudentId = "B10000001"
        credentials.ntustPassword = "school"
        libraryAccount("B10000001")

        assertTrue(credentials.signOutSchoolAccount())

        assertNull(credentials.ntustStudentId)
        assertNull(credentials.ntustPassword)
        assertNull(credentials.libraryUsername)
    }

    @Test
    fun `another student's library account stays through the school sign-out`() {
        credentials.ntustStudentId = "B10000001"
        credentials.ntustPassword = "school"
        libraryAccount("B10000009")

        assertFalse(credentials.signOutSchoolAccount())

        assertNull(credentials.ntustStudentId)
        assertEquals("B10000009", credentials.libraryUsername)
    }

    @Test
    fun `the library account of the same student goes, whatever the case`() {
        libraryAccount("b10000001")

        assertTrue(credentials.clearLibraryCredentials(ifSchoolAccount = "B10000001"))
        assertNull(credentials.libraryUsername)
        assertNull(credentials.libraryPassword)
    }

    @Test
    fun `a library account of another student stays`() {
        libraryAccount("B10000009")

        assertFalse(credentials.clearLibraryCredentials(ifSchoolAccount = "B10000001"))
        assertEquals("B10000009", credentials.libraryUsername)
        assertTrue(credentials.isLibraryTokenValid)
    }

    @Test
    fun `without a stored school account the library account stays`() {
        libraryAccount("B10000009")

        assertFalse(credentials.clearLibraryCredentials(ifSchoolAccount = null))
        assertEquals("B10000009", credentials.libraryUsername)
    }

    @Test
    fun `the school sign-in's library step still out stores nothing`() {
        libraryAccount("B10000009")
        val generation = credentials.libraryAccountGeneration
        val school = credentials.schoolAccountGeneration

        credentials.clearLibraryCredentials(ifSchoolAccount = "B10000001")

        assertFalse(
            credentials.saveLibrarySignIn(generation, "B10000001", "school", "theirs", Long.MAX_VALUE, byUser = false) {
                credentials.isSchoolAccount(school)
            },
        )
        assertEquals("B10000009", credentials.libraryUsername)
    }

    @Test
    fun `a library sign-in the user asked for still stores`() {
        libraryAccount("B10000009")
        val generation = credentials.libraryAccountGeneration

        credentials.clearLibraryCredentials(ifSchoolAccount = "B10000001")

        assertTrue(credentials.saveLibrarySignIn(generation, "B10000009", "new", "token", Long.MAX_VALUE, byUser = true))
    }

    /**
     * Another student's library account, signing in again with its stored
     * credentials while the school account signs out: the sign-out keeps that
     * account, so what the library answers is still about its password.
     */
    private fun storedSignInDuringSchoolSignOut(): FakeLibraryServer {
        credentials.ntustStudentId = "B10000001"
        assertTrue(
            credentials.saveLibrarySignIn(credentials.libraryAccountGeneration, "B10000009", "pw", "expired", 1L, byUser = true),
        )
        return FakeLibraryServer().apply { whileSigningIn = { credentials.signOutSchoolAccount() } }
    }

    @Test
    fun `a refusal found as another student signs out of the school is kept`() = runBlocking {
        val server = storedSignInDuringSchoolSignOut()
        server.signInAnswers += FakeLibraryServer.WRONG_PASSWORD
        val service = server.service(credentials)

        assertTrue(runCatching { service.ensureToken() }.exceptionOrNull() is LibraryServiceError.LoginFailed)

        assertTrue(credentials.libraryPasswordRefused)
        // Each refusal counts toward the account's lockout: the password is not sent again.
        assertTrue(runCatching { service.ensureToken() }.exceptionOrNull() is LibraryServiceError.CredentialsNotFound)
        assertFalse(service.signInIfCredentialed { true })
        assertEquals(1, server.signIns)
    }

    @Test
    fun `a token renewed as another student signs out of the school is kept`() = runBlocking {
        val service = storedSignInDuringSchoolSignOut().service(credentials)

        assertEquals("token-1", service.ensureToken())

        assertEquals("B10000009", credentials.libraryUsername)
        assertEquals("token-1", credentials.libraryToken)
        assertTrue(credentials.isLibraryTokenValid)
    }
}
