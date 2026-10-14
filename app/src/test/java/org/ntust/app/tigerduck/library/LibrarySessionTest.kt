package org.ntust.app.tigerduck.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySessionTest {

    @Test
    fun `refresh picks up a sign-in and a sign-out made behind its back`() {
        var signedIn = false
        val session = LibrarySession(read = { signedIn })
        assertFalse(session.signedIn.value)

        signedIn = true
        assertTrue(session.refresh())
        assertTrue(session.signedIn.value)

        signedIn = false
        assertFalse(session.refresh())
        assertFalse(session.signedIn.value)
    }

    @Test
    fun `signed out, the kept QR code goes`() {
        var signedIn = true
        var cleared = 0
        val session = LibrarySession({ signedIn }, onSignedOut = { cleared++ })

        session.refresh()
        assertEquals(0, cleared)

        signedIn = false
        session.refresh()
        assertEquals(1, cleared)
    }
}
