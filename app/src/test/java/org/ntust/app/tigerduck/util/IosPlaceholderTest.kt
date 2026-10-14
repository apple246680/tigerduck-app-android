package org.ntust.app.tigerduck.util

import org.junit.Assert.assertEquals
import org.junit.Test

class IosPlaceholderTest {

    @Test
    fun `a string argument fills its iOS placeholder`() {
        assertEquals("To: a@b.c", "To: %1\$@".replaceIosArg(1, "a@b.c"))
    }

    @Test
    fun `a whole number fills an iOS long long placeholder`() {
        assertEquals("Wednesday, 3 events", "%1\$@, %2\$lld events".replaceIosArg(1, "Wednesday").replaceIosArg(2, 3L))
    }

    @Test
    fun `a whole number fills a plain integer placeholder`() {
        assertEquals("Color 2", "Color %1\$d".replaceIosArg(1, 2L))
    }
}
