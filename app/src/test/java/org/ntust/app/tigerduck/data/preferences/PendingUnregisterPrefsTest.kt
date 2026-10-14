package org.ntust.app.tigerduck.data.preferences

import android.app.Application
import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.DepartingRefresh
import org.ntust.app.tigerduck.auth.DepartingSession
import org.ntust.app.tigerduck.push.DepartingDeviceUnregister
import org.ntust.app.tigerduck.push.OwedUnregister
import org.ntust.app.tigerduck.push.PendingUnregister
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** The owed unregister outlasts the sign-out and the process; see DepartingDeviceUnregister. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PendingUnregisterPrefsTest {

    private val prefs = RuntimeEnvironment.getApplication()
        .getSharedPreferences("pending-unregister-test", Context.MODE_PRIVATE)

    @Test
    fun `what is owed reads back after a restart, and is gone once cleared`() {
        PendingUnregisterPrefs(prefs).save(PendingUnregister("device-1", DepartingSession("access-1", "refresh-1", 42L, "https://backend.example/v3"), 7L))

        // A new instance over the same file, as the next launch has.
        val read = PendingUnregisterPrefs(prefs).load()
        assertEquals("device-1", read?.deviceId)
        assertEquals("access-1", read?.session?.accessToken)
        assertEquals("refresh-1", read?.session?.refreshToken)
        assertEquals(42L, read?.session?.accessExpiresAt)
        assertEquals("https://backend.example/v3", read?.session?.baseUrl)
        assertEquals(7L, read?.owedAt)

        PendingUnregisterPrefs(prefs).clear()
        assertNull(PendingUnregisterPrefs(prefs).load())
    }

    @Test
    fun `a sign-out's clearing of the session leaves what is owed`() {
        PendingUnregisterPrefs(prefs).save(PendingUnregister("device-1", DepartingSession("access-1", "refresh-1", 42L, "https://backend.example/v3"), 7L))

        // What CredentialManager.clearNtustCredentials() removes of the session.
        prefs.edit()
            .remove(V3TokenStore.KEY_ACCESS_TOKEN)
            .remove(V3TokenStore.KEY_REFRESH_TOKEN)
            .remove(V3TokenStore.KEY_EXPIRES_AT)
            .commit()

        assertEquals("refresh-1", PendingUnregisterPrefs(prefs).load()?.session?.refreshToken)
    }

    // The full reset signs out first, so an unregister that failed offline is owed while the
    // reset wipes the credentials.

    private fun owe() = PendingUnregisterPrefs(prefs)
        .save(
            PendingUnregister(
                "device-1",
                DepartingSession("access-1", "refresh-1", Long.MAX_VALUE, "https://backend.example/v3", "B11300001"),
                0L,
            ),
        )

    @Test
    fun `the full reset's wipe keeps what is owed and takes every credential else`() {
        prefs.edit()
            .putString("ntust_student_id", "B11300001")
            .putString("library_token", "library")
            .putString("mail_password", "mail")
            .commit()
        V3TokenStore(prefs).save("access-2", "refresh-2", 99L)
        owe()

        // What CredentialManager.clearAll() runs.
        clearAllButOwedUnregister(prefs)

        assertEquals("refresh-1", PendingUnregisterPrefs(prefs).load()?.session?.refreshToken)
        assertEquals(PendingUnregisterPrefs.KEYS, prefs.all.keys)
    }

    @Test
    fun `after the reset the debt goes out, unless a backend sign-in came first`() = runTest {
        owe()
        clearAllButOwedUnregister(prefs)
        val tokens = V3TokenStore(prefs)
        val deletes = mutableListOf<String>()
        fun unregister() = DepartingDeviceUnregister(
            owed = OwedUnregister(PendingUnregisterPrefs(prefs)),
            deleteDevice = { _, device, token -> deletes += "$device $token"; 204 },
            refresh = { DepartingRefresh.Refused },
            hasSession = { tokens.refreshToken != null },
            currentBaseUrl = { "https://backend.example/v3" },
            now = { 0L },
        )

        // A backend sign-in after the reset moved the device to its account.
        tokens.save("access-2", "refresh-2", Long.MAX_VALUE)
        assertEquals(DepartingDeviceUnregister.Outcome.DONE, unregister().send())
        assertEquals(emptyList<String>(), deletes)
        assertNull(PendingUnregisterPrefs(prefs).load())

        // Without one, the departing account's token goes out.
        tokens.clear()
        owe()
        clearAllButOwedUnregister(prefs)
        assertEquals(DepartingDeviceUnregister.Outcome.DONE, unregister().send())
        assertEquals(listOf("device-1 access-1"), deletes)
        assertNull(PendingUnregisterPrefs(prefs).load())
    }

    @Test
    fun `the full reset's credential wipe is the one that keeps the debt`() {
        // CredentialManager needs the Android keystore, so this reads its source.
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "src/main/java").isDirectory && !File(dir, "app/src/main/java").isDirectory) {
            dir = dir.parentFile
        }
        val app = if (File(dir, "app/src/main/java").isDirectory) File(dir, "app") else dir!!
        val source = File(app, "src/main/java/org/ntust/app/tigerduck/data/preferences/CredentialManager.kt").readText()
        val clearAll = source.substringAfter("fun clearAll()").substringBefore("\n    }")

        assertTrue(clearAll, "clearAllButOwedUnregister(prefs)" in clearAll)
        assertFalse(clearAll, ".clear()" in clearAll)
    }
}
