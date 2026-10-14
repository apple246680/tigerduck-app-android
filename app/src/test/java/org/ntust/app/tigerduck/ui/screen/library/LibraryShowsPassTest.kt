package org.ntust.app.tigerduck.ui.screen.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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
}
