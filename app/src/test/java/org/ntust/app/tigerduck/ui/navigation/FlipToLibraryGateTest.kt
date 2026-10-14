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

    @Test
    fun `the gesture is offered once signed in to the library, with the switch off and never asked`() {
        assertTrue(FlipToLibraryGate.offersGesture(signedInToLibrary = true, enabled = false, asked = false, onLibraryPage = false))
    }

    @Test
    fun `the gesture is not offered signed out, already on, or once answered`() {
        assertFalse(FlipToLibraryGate.offersGesture(signedInToLibrary = false, enabled = false, asked = false, onLibraryPage = false))
        assertFalse(FlipToLibraryGate.offersGesture(signedInToLibrary = true, enabled = true, asked = false, onLibraryPage = false))
        assertFalse(FlipToLibraryGate.offersGesture(signedInToLibrary = true, enabled = false, asked = true, onLibraryPage = false))
    }

    /** It would cover the code someone just signed in to show (tigerduck-app/tigerduck-app#529). */
    @Test
    fun `the gesture is not offered over the Library page, and leaving it asks`() {
        assertFalse(FlipToLibraryGate.offersGesture(signedInToLibrary = true, enabled = false, asked = false, onLibraryPage = true))
        assertTrue(FlipToLibraryGate.offersGesture(signedInToLibrary = true, enabled = false, asked = false, onLibraryPage = false))
    }

    /**
     * A cold start composes the effect before the back stack has a page, and the first page can
     * be Library, as the first tab or from the library shortcut: the offer waits for it.
     */
    @Test
    fun `the offer waits while no page is shown yet, and on the Library page`() {
        assertTrue(FlipToLibraryGate.offerWaitsForPage(currentRoute = null))
        assertTrue(FlipToLibraryGate.offerWaitsForPage(currentRoute = Screen.Library.route))
        assertFalse(FlipToLibraryGate.offerWaitsForPage(currentRoute = Screen.Home.route))
    }
}
