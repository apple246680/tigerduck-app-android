package org.ntust.app.tigerduck.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Moodle token a sign-in harvested is stored only while that sign-in is still the account's:
 * a sign-out during the harvest leaves none of the departing student's behind
 * (https://github.com/tigerduck-app/tigerduck-app-android/issues/300).
 */
class MoodleTokenSlotTest {

    private var slot: String? = null

    @Test
    fun `a sign-in still current stores its token`() {
        assertTrue(storeIfStillCurrent("token", ::slot) { true })

        assertEquals("token", slot)
    }

    @Test
    fun `a sign-in that ended stores nothing`() {
        assertFalse(storeIfStillCurrent("token", ::slot) { false })

        assertNull(slot)
    }

    @Test
    fun `a sign-out that lands during the write takes the token back out`() {
        val answers = ArrayDeque(listOf(true, false))

        assertFalse(storeIfStillCurrent("token", ::slot) { answers.removeFirst() })

        assertNull(slot)
    }

    @Test
    fun `taking it back leaves a token someone else stored since`() {
        var checks = 0

        assertFalse(
            storeIfStillCurrent("token", ::slot) {
                if (checks++ == 0) {
                    true
                } else {
                    slot = "next-account"
                    false
                }
            },
        )

        assertEquals("next-account", slot)
    }
}
