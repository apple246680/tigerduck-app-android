package org.ntust.app.tigerduck.data.preferences

import android.app.Application
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The library's refusal of the stored password is kept across launches, so the
 * app never sends it again by itself, until credentials that work are saved or
 * the library account signs out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CredentialManagerRefusedPasswordTest {

    // Plain preferences: Robolectric has no Android keystore for the encrypted ones.
    private val prefs = RuntimeEnvironment.getApplication()
        .getSharedPreferences("credentials-refused-test", Context.MODE_PRIVATE)
    private lateinit var credentials: CredentialManager

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        credentials = CredentialManager(prefs)
        credentials.saveLibrarySignIn(credentials.libraryAccountGeneration, "B10000001", "pw", "token", 1L, byUser = true)
    }

    @Test
    fun `a refusal is kept across launches`() {
        assertTrue(credentials.refuseLibraryPassword(credentials.libraryAccountGeneration, "pw"))

        assertTrue(CredentialManager(prefs).libraryPasswordRefused)
    }

    @Test
    fun `credentials saved by a sign-in that works clear it`() {
        credentials.refuseLibraryPassword(credentials.libraryAccountGeneration, "pw")

        credentials.saveLibrarySignIn(credentials.libraryAccountGeneration, "B10000001", "new", "token", Long.MAX_VALUE, byUser = true)

        assertFalse(credentials.libraryPasswordRefused)
    }

    @Test
    fun `a library sign-out clears it`() {
        credentials.refuseLibraryPassword(credentials.libraryAccountGeneration, "pw")

        credentials.clearLibraryCredentials()

        assertFalse(credentials.libraryPasswordRefused)
    }

    @Test
    fun `a refusal of a password no longer stored, or of an account that left, is not kept`() {
        val generation = credentials.libraryAccountGeneration
        assertFalse(credentials.refuseLibraryPassword(generation, "old"))

        credentials.clearLibraryCredentials()
        credentials.saveLibrarySignIn(credentials.libraryAccountGeneration, "B10000001", "pw", "token", 1L, byUser = true)
        assertFalse(credentials.refuseLibraryPassword(generation, "pw"))
        assertFalse(credentials.libraryPasswordRefused)
    }
}
