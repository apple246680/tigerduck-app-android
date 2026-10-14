package org.ntust.app.tigerduck.wear.data

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.shared.LibraryCredentialStore
import org.ntust.app.tigerduck.shared.LibraryService
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * The watch's library QR page shows its generic "Failed to generate QR code"
 * over the reason a fetch failed. A failure without a message has no reason
 * to give, and its class name is none: a release build shows R8's short name
 * for it, such as "a".
 */
@RunWith(RobolectricTestRunner::class)
class LibraryQRControllerTest {

    /** No library account on the watch, so the fetch throws CredentialsNotFound, which has no message. */
    private object NoAccount : LibraryCredentialStore {
        override var libraryUsername: String? = null
        override var libraryPassword: String? = null
        override var libraryToken: String? = null
        override var libraryTokenExpiry: Long = 0
        override val isLibraryTokenValid = false
    }

    @Test
    fun `a failed fetch without a message adds nothing to the generic error`() = runBlocking {
        val controller = LibraryQRController(LibraryService(NoAccount), this)
        controller.start(qrSidePx = 64)
        assertEquals("", withTimeout(5_000) { controller.error.filterNotNull().first() })
    }

    @Test
    fun `a failure's own message is still the reason shown`() {
        assertEquals("Unable to resolve host", qrErrorDetail(IOException("Unable to resolve host")))
        assertEquals("", qrErrorDetail(IOException(" ")))
        assertEquals("", qrErrorDetail(IllegalStateException()))
    }
}
