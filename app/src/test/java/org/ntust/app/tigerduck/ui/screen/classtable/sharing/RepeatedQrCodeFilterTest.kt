package org.ntust.app.tigerduck.ui.screen.classtable.sharing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A code kept in front of the camera is passed on once, and again only after it left the picture. */
class RepeatedQrCodeFilterTest {

    @Test
    fun `a code read on frame after frame passes once`() {
        val filter = RepeatedQrCodeFilter()

        assertTrue(filter.isNew("A", 0))
        assertFalse(filter.isNew("A", 33))
        assertFalse(filter.isNew("A", 1_000))
        assertFalse(filter.isNew("A", 1_900))
    }

    @Test
    fun `a code out of view for over a second passes again, and other codes pass meanwhile`() {
        val filter = RepeatedQrCodeFilter()

        assertTrue(filter.isNew("A", 0))
        assertTrue(filter.isNew("B", 500))
        assertTrue(filter.isNew("A", 1_001))
        assertFalse(filter.isNew("B", 1_400))
    }
}
