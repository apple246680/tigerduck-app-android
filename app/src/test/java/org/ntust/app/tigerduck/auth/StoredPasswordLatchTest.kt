package org.ntust.app.tigerduck.auth

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.network.MoodleWebserviceError
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The app-wide latch that keeps automatic sign-ins from posting a password the school refused
 * (https://github.com/tigerduck-app/tigerduck-app-android/issues/163), and the guard the
 * automatic Moodle token harvest runs under.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class StoredPasswordLatchTest {

    private lateinit var prefs: SharedPreferences
    private var stored: Pair<String, String>? = "B00000000" to "old-password"

    @Before
    fun setUp() {
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences("latch-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    private fun latch() = PersistedStoredPasswordLatch(prefs) { stored }

    @Test
    fun `a refusal latches with the school's message, and a later process still sees it`() {
        val latch = latch()

        latch.markRejected(latch.generation, "B00000000", "old-password", "  Wrong account or password  ")

        assertTrue(latch.isRejected)
        assertEquals("Wrong account or password", latch.rejection.value?.reason)
        val restarted = latch()
        assertTrue(restarted.isRejected)
        assertEquals("Wrong account or password", restarted.rejection.value?.reason)
    }

    @Test
    fun `nothing is latched on a fresh install`() {
        assertFalse(latch().isRejected)
    }

    @Test
    fun `clearing lifts it, in this process and the next`() {
        val latch = latch()
        latch.markRejected(latch.generation, "B00000000", "old-password", null)

        latch.clear()

        assertFalse(latch.isRejected)
        assertFalse(latch().isRejected)
    }

    @Test
    fun `a refusal that arrives after a clear latches nothing`() {
        val latch = latch()
        val before = latch.generation
        // The user signed in by hand while the automatic sign-in waited for its answer.
        latch.clear()

        latch.markRejected(before, "B00000000", "old-password", null)

        assertFalse(latch.isRejected)
    }

    @Test
    fun `a refusal of a password that is no longer the stored one latches nothing`() {
        val latch = latch()
        stored = "B00000000" to "new-password"

        latch.markRejected(latch.generation, "B00000000", "old-password", null)

        assertFalse(latch.isRejected)
    }

    @Test
    fun `a refusal after the account signed out, or for another student, latches nothing`() {
        val latch = latch()
        stored = null
        latch.markRejected(latch.generation, "B00000000", "old-password", null)
        assertFalse(latch.isRejected)

        stored = "B11111111" to "old-password"
        latch.markRejected(latch.generation, "B00000000", "old-password", null)
        assertFalse(latch.isRejected)
    }

    @Test
    fun `the student ID is compared as the sign-in normalizes it`() {
        val latch = latch()

        latch.markRejected(latch.generation, " b00000000 ", "old-password", null)

        assertTrue(latch.isRejected)
    }

    @Test
    fun `each refusal is its own, even with the same message`() {
        val latch = latch()
        latch.markRejected(latch.generation, "B00000000", "old-password", "Locked")
        val first = latch.rejection.value

        latch.clear()
        latch.markRejected(latch.generation, "B00000000", "old-password", "Locked")

        assertNotSame(first, latch.rejection.value)
    }

    @Test
    fun `an automatic harvest while latched posts nothing and fails as SSO's refusal`() = runTest {
        val latch = latch()
        latch.markRejected(latch.generation, "B00000000", "old-password", "Locked")
        var harvested = false

        val failure = runCatching {
            latch.guardAutomaticHarvest("B00000000", "old-password") { harvested = true }
        }.exceptionOrNull()

        assertFalse(harvested)
        assertTrue("got $failure", failure is MoodleWebserviceError.SsoLoginRejected)
        assertEquals("Locked", (failure as MoodleWebserviceError.SsoLoginRejected).reason)
    }

    @Test
    fun `an automatic harvest SSO refuses latches`() = runTest {
        val latch = latch()

        val failure = runCatching {
            latch.guardAutomaticHarvest("B00000000", "old-password") {
                throw MoodleWebserviceError.SsoLoginRejected("Wrong password")
            }
        }.exceptionOrNull()

        assertTrue("got $failure", failure is MoodleWebserviceError.SsoLoginRejected)
        assertEquals("Wrong password", latch.rejection.value?.reason)
    }

    @Test
    fun `an automatic harvest that fails for another reason latches nothing`() = runTest {
        val latch = latch()

        runCatching {
            latch.guardAutomaticHarvest("B00000000", "old-password") {
                throw MoodleWebserviceError.TransientNetwork("timeout")
            }
        }

        assertNull(latch.rejection.value)
    }
}
