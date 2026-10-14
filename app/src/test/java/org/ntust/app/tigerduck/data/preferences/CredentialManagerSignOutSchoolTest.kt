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
 * The school sign-out AuthService makes reads the departing student ID before
 * it wipes the school account, so the same student's library account goes
 * with it and another student's stays.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CredentialManagerSignOutSchoolTest {

    // Plain preferences: Robolectric has no Android keystore for the encrypted ones.
    private val prefs = RuntimeEnvironment.getApplication()
        .getSharedPreferences("credentials-sign-out-school-test", Context.MODE_PRIVATE)
    private lateinit var credentials: CredentialManager

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        credentials = CredentialManager(prefs)
        credentials.ntustStudentId = "B10000001"
        credentials.ntustPassword = "school"
    }

    private fun libraryAccount(username: String) {
        credentials.saveLibrarySignIn(credentials.libraryAccountGeneration, username, "pw", "token", Long.MAX_VALUE, byUser = true)
    }

    @Test
    fun `the same student's library account goes with the school account`() {
        libraryAccount("B10000001")

        assertTrue(credentials.signOutSchoolAccount())

        assertNull(credentials.ntustStudentId)
        assertNull(credentials.ntustPassword)
        assertNull(credentials.libraryUsername)
    }

    @Test
    fun `another student's library account stays`() {
        libraryAccount("B10000009")

        assertFalse(credentials.signOutSchoolAccount())

        assertNull(credentials.ntustStudentId)
        assertEquals("B10000009", credentials.libraryUsername)
    }
}
