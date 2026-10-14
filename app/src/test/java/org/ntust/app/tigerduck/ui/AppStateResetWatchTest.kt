package org.ntust.app.tigerduck.ui

import android.app.Application
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.library.libraryCredentialsForWatch
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A full reset leaves no library account on the watch, another student's included. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AppStateResetWatchTest {

    @Test
    fun `the watch is told once every credential is gone`() = runBlocking {
        // Plain preferences: Robolectric has no Android keystore for the encrypted ones.
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("credentials-reset-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val credentials = CredentialManager(prefs)
        credentials.ntustStudentId = "B10000001"
        // Another student's library account, which a school sign-out keeps.
        credentials.saveLibrarySignIn(credentials.libraryAccountGeneration, "B10000009", "pw", "token", Long.MAX_VALUE, byUser = true)
        val handedToWatch = mutableListOf<Pair<String, String>?>()

        clearAllCredentials(credentials) { handedToWatch += libraryCredentialsForWatch(credentials) }

        assertEquals(1, handedToWatch.size)
        assertNull(handedToWatch.single())
    }
}
