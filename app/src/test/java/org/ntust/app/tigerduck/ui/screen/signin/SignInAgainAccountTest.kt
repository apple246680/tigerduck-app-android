package org.ntust.app.tigerduck.ui.screen.signin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Sign in again" signs in as the stored account only: another student would inherit what the
 * stored one left on the device, so that one signs out in Settings first.
 */
class SignInAgainAccountTest {

    @Test
    fun `the stored account may sign in again, however it is typed`() {
        assertTrue(signsInAsStoredAccount(typed = " b11300001 ", stored = "B11300001"))
    }

    @Test
    fun `another student may not`() {
        assertFalse(signsInAsStoredAccount(typed = "B11300002", stored = "B11300001"))
    }

    @Test
    fun `with no account stored, anyone may`() {
        assertTrue(signsInAsStoredAccount(typed = "B11300002", stored = null))
    }
}
