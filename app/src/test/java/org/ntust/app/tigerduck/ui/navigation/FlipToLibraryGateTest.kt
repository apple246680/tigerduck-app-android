package org.ntust.app.tigerduck.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.mail.MailRoutes

/**
 * A face-down flip used to jump to Library whatever was open, dropping a half-typed rename,
 * the colour sheet, a confirmation or a mail draft with the screen that held it. The iPhone
 * ignores the flip while a sheet or alert is up.
 */
class FlipToLibraryGateTest {

    @Test
    fun `a flip on an ordinary page goes to Library`() {
        assertTrue(FlipToLibraryGate.mayNavigate(windowFocused = true, currentRoute = Screen.ClassTable.route))
        assertTrue(FlipToLibraryGate.mayNavigate(windowFocused = true, currentRoute = Screen.Settings.route))
    }

    @Test
    fun `a flip while a dialog, sheet or menu has the focus does nothing`() {
        // Each of those is a window of its own, which takes the focus from the app's.
        assertFalse(FlipToLibraryGate.mayNavigate(windowFocused = false, currentRoute = Screen.ClassTable.route))
    }

    @Test
    fun `a flip with a mail draft on screen does nothing`() {
        // The iPhone writes mail in a sheet, which the flip already leaves alone.
        assertFalse(FlipToLibraryGate.mayNavigate(windowFocused = true, currentRoute = MailRoutes.COMPOSE))
    }
}
