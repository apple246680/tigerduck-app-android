package org.ntust.app.tigerduck.ui.screen.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.ui.screen.library.LibraryViewModel.Companion.qrAccount
import org.ntust.app.tigerduck.ui.screen.library.LibraryViewModel.Companion.showsPass

class LibraryShowsPassTest {

    @Test
    fun `an expired token with stored credentials opens on the pass, which signs in again`() {
        assertTrue(showsPass(fixtureSignedIn = false, tokenValid = false, canSignInWithStoredCredentials = true))
    }

    @Test
    fun `a refused password or no account opens on the sign-in form`() {
        assertFalse(showsPass(fixtureSignedIn = false, tokenValid = false, canSignInWithStoredCredentials = false))
    }

    @Test
    fun `a live token or the demo library opens on the pass`() {
        assertTrue(showsPass(fixtureSignedIn = false, tokenValid = true, canSignInWithStoredCredentials = false))
        assertTrue(showsPass(fixtureSignedIn = true, tokenValid = false, canSignInWithStoredCredentials = false))
    }

    @Test
    fun `the demo library's code is not shown again for a real account, nor one account's for the next`() {
        assertNotEquals(qrAccount(generation = 3, demoLibrary = true), qrAccount(generation = 3, demoLibrary = false))
        assertNotEquals(qrAccount(generation = 3, demoLibrary = false), qrAccount(generation = 4, demoLibrary = false))
        assertNotEquals(qrAccount(generation = 3, demoLibrary = true), qrAccount(generation = 4, demoLibrary = false))
    }
}
