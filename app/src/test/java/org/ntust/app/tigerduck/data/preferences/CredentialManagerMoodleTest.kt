package org.ntust.app.tigerduck.data.preferences

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The Moodle tokens kept with the NTUST account, and gone with it. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CredentialManagerMoodleTest {

    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("credentials-test", Context.MODE_PRIVATE)
    private val credentials = CredentialManager(context = null, givenPrefs = prefs)

    @Test
    fun `the private token is stored with the token while the account is signed in, and goes at sign-out`() {
        credentials.ntustStudentId = "B11300001"
        assertFalse(credentials.storeMoodleTokenIfSignedIn("B11300002", "token", "private"))
        assertNull(credentials.moodlePrivateToken)

        credentials.storeMoodleTokenIfSignedIn("B11300001", "token", "private")
        assertEquals("private", credentials.moodlePrivateToken)

        credentials.clearNtustCredentials()

        assertNull(credentials.moodleToken)
        assertNull(credentials.moodlePrivateToken)
        assertFalse(prefs.contains("moodle_private_token"))
    }

    @Test
    fun `a full reset takes the private token too`() {
        credentials.ntustStudentId = "B11300001"
        credentials.storeMoodleTokenIfSignedIn("B11300001", "token", "private")

        credentials.clearAll()

        assertNull(credentials.moodlePrivateToken)
    }
}
