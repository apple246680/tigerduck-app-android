package org.ntust.app.tigerduck.data.preferences

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A school sign-out takes the library account of the same student ID, and
 * leaves one signed in with another ID, as on the iPhone.
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

        credentials.clearLibraryCredentials(ifSchoolAccount = "B10000001")

        assertFalse(credentials.saveLibrarySignIn(generation, "B10000001", "school", "theirs", Long.MAX_VALUE, byUser = false))
        assertEquals("B10000009", credentials.libraryUsername)
    }

    @Test
    fun `a library sign-in the user asked for still stores`() {
        libraryAccount("B10000009")
        val generation = credentials.libraryAccountGeneration

        credentials.clearLibraryCredentials(ifSchoolAccount = "B10000001")

        assertTrue(credentials.saveLibrarySignIn(generation, "B10000009", "new", "token", Long.MAX_VALUE, byUser = true))
    }
}
