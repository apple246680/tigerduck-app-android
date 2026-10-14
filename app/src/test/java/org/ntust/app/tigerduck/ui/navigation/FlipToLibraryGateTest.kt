package org.ntust.app.tigerduck.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Flip-to-library while no library account is signed in (tigerduck-app/tigerduck-app#495). */
class FlipToLibraryGateTest {

    @Test
    fun `the flip does not listen while signed out of the library`() {
        assertFalse(FlipToLibraryGate.isArmed(enabled = true, signedInToLibrary = false))
    }

    @Test
    fun `the flip listens with the switch on and a library account signed in`() {
        assertTrue(FlipToLibraryGate.isArmed(enabled = true, signedInToLibrary = true))
        assertFalse(FlipToLibraryGate.isArmed(enabled = false, signedInToLibrary = true))
    }

    @Test
    fun `signed out, the switch reads off and takes no input`() {
        assertFalse(FlipToLibraryGate.switchReadsOn(enabled = true, signedInToLibrary = false))
        assertFalse(FlipToLibraryGate.switchTakesInput(signedInToLibrary = false))
    }

    @Test
    fun `signed in, the switch shows the choice it holds`() {
        assertTrue(FlipToLibraryGate.switchReadsOn(enabled = true, signedInToLibrary = true))
        assertFalse(FlipToLibraryGate.switchReadsOn(enabled = false, signedInToLibrary = true))
        assertTrue(FlipToLibraryGate.switchTakesInput(signedInToLibrary = true))
    }
}
