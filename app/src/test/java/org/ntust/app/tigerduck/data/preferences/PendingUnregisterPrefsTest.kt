package org.ntust.app.tigerduck.data.preferences

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.DepartingSession
import org.ntust.app.tigerduck.push.PendingUnregister
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The owed unregister outlasts the sign-out and the process; see DepartingDeviceUnregister. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PendingUnregisterPrefsTest {

    private val prefs = RuntimeEnvironment.getApplication()
        .getSharedPreferences("pending-unregister-test", Context.MODE_PRIVATE)

    @Test
    fun `what is owed reads back after a restart, and is gone once cleared`() {
        PendingUnregisterPrefs(prefs).save(PendingUnregister("device-1", DepartingSession("access-1", "refresh-1", 42L)))

        // A new instance over the same file, as the next launch has.
        val read = PendingUnregisterPrefs(prefs).load()
        assertEquals("device-1", read?.deviceId)
        assertEquals("access-1", read?.session?.accessToken)
        assertEquals("refresh-1", read?.session?.refreshToken)
        assertEquals(42L, read?.session?.accessExpiresAt)

        PendingUnregisterPrefs(prefs).clear()
        assertNull(PendingUnregisterPrefs(prefs).load())
    }

    @Test
    fun `a sign-out's clearing of the session leaves what is owed`() {
        PendingUnregisterPrefs(prefs).save(PendingUnregister("device-1", DepartingSession("access-1", "refresh-1", 42L)))

        // What CredentialManager.clearNtustCredentials() removes of the session.
        prefs.edit()
            .remove(V3TokenStore.KEY_ACCESS_TOKEN)
            .remove(V3TokenStore.KEY_REFRESH_TOKEN)
            .remove(V3TokenStore.KEY_EXPIRES_AT)
            .commit()

        assertEquals("refresh-1", PendingUnregisterPrefs(prefs).load()?.session?.refreshToken)
    }
}
