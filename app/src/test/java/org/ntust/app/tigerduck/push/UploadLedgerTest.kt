package org.ntust.app.tigerduck.push

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The background worker posted the whole assignment list every 15 minutes,
 * and each upload pushed the server's own sync back two hours
 * (tigerduck-app-android#220). A list the server accepted goes again only
 * once it changes, or six hours on, as on the iPhone ([UploadLedger]).
 */
class UploadLedgerTest {

    private var now = 1_000_000L
    private var sends = 0

    private val store = object : UploadLedgerStore {
        override var fingerprint: String? = null
        override var acceptedAtMs = 0L
        override fun record(fingerprint: String, atMs: Long) {
            this.fingerprint = fingerprint
            acceptedAtMs = atMs
        }
    }

    private val ledger = UploadLedger(store) { now }

    private suspend fun upload(list: String, current: Boolean = true) =
        ledger.sendIfDue(uploadFingerprint(list), { current }) { sends++ }

    @Test
    fun `an unchanged list is not sent again until six hours on`() = runTest {
        assertTrue(upload("[a,b]"))
        now += 15 * 60_000L
        assertFalse(upload("[a,b]"))
        now += UNCHANGED_UPLOAD_INTERVAL_MS
        assertTrue(upload("[a,b]"))
        assertEquals(2, sends)
    }

    @Test
    fun `a changed list is sent at once`() = runTest {
        upload("[a,b]")
        now += 60_000L

        assertTrue(upload("[a,b,c]"))
        assertEquals(2, sends)
    }

    @Test
    fun `a failed upload is not recorded, so the next one goes`() = runTest {
        runCatching { ledger.sendIfDue(uploadFingerprint("[a]"), { true }) { throw IOException("offline") } }

        assertTrue(upload("[a]"))
    }

    @Test
    fun `an upload that outlived a sign-out records nothing`() = runTest {
        upload("[a]", current = false)

        assertTrue(upload("[a]"))
    }

    @Test
    fun `a clock moved back past the last upload sends again`() {
        assertTrue(uploadIsDue("x", acceptedAtMs = 5_000L, fingerprint = "x", nowMs = 4_000L))
        assertFalse(uploadIsDue("x", acceptedAtMs = 5_000L, fingerprint = "x", nowMs = 6_000L))
    }

    @Test
    fun `the same list gives the same fingerprint, another list another`() {
        assertEquals(uploadFingerprint("[1,2]"), uploadFingerprint("[1,2]"))
        assertNotEquals(uploadFingerprint("[1,2]"), uploadFingerprint("[1,3]"))
    }
}
