package org.ntust.app.tigerduck.ui.screen.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReenableResolutionsTest {

    @Test
    fun `a check runs at once when no answer is being applied`() {
        assertFalse(ReenableResolutions().defers { })
    }

    @Test
    fun `a check waits for the answer being applied and runs when it finishes`() {
        val resolutions = ReenableResolutions()
        var checks = 0
        resolutions.start()

        assertTrue(resolutions.defers { checks++ })
        assertEquals(0, checks)
        // Letting the hold go here would end it under the check's own dialog.
        assertFalse(resolutions.finish())
        assertEquals(1, checks)
        assertFalse(resolutions.defers { checks++ })
    }

    @Test
    fun `the hold is let go when the last answer finishes and no check waits`() {
        val resolutions = ReenableResolutions()
        resolutions.start()

        assertTrue(resolutions.finish())
    }

    @Test
    fun `an answer finishing first keeps the hold for one still being applied`() {
        val resolutions = ReenableResolutions()
        var checks = 0
        resolutions.start()
        resolutions.start()
        resolutions.defers { checks++ }

        assertFalse(resolutions.finish())
        assertEquals(0, checks)
        assertFalse(resolutions.finish())
        assertEquals(1, checks)
    }
}
