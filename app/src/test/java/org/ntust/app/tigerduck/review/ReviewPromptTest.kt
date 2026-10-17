package org.ntust.app.tigerduck.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.preferences.ReviewPromptStore
import java.time.LocalDate

/**
 * The rule behind the Play Store rating request
 * (tigerduck-app-android#332): asked once, ever, at a calm moment after the
 * class table has loaded on [ReviewPrompt.DAYS_OF_USE] different days.
 *
 * Flavor-neutral: the same rule runs on fdroid, where the launcher is a stub
 * that never launches, so nothing is recorded as asked there.
 */
class ReviewPromptTest {

    /** What a fresh install's preferences hold: nothing. */
    private class FakeStore : ReviewPromptStore {
        override var reviewPromptDaysUsed: Int = 0
        override var reviewPromptLastDay: String? = null
        override var reviewPromptAsked: Boolean = false
    }

    private val store = FakeStore()
    private var today: LocalDate = LocalDate.of(2026, 10, 12)
    private var mayAsk = true

    /** A process: the prompt keeps its in-memory state, the store outlives it. */
    private fun process() = ReviewPrompt(store, { today }, { mayAsk })

    private fun days(count: Int): List<LocalDate> =
        (0 until count).map { LocalDate.of(2026, 10, 1).plusDays(it.toLong()) }

    private fun ReviewPrompt.loadedOn(days: List<LocalDate>) {
        for (day in days) {
            today = day
            classTableLoaded()
        }
    }

    @Test
    fun `does not ask before the class table has loaded on five days`() {
        val prompt = process()
        prompt.loadedOn(days(4))
        prompt.calmMoment()
        assertFalse(prompt.pending.value)
        assertEquals(4, store.reviewPromptDaysUsed)
    }

    @Test
    fun `a day counts once however often the class table loads`() {
        val prompt = process()
        repeat(6) { prompt.classTableLoaded() }
        prompt.calmMoment()
        assertFalse(prompt.pending.value)
        assertEquals(1, store.reviewPromptDaysUsed)
        assertEquals("2026-10-12", store.reviewPromptLastDay)
    }

    @Test
    fun `asks at the first calm moment on the fifth day of use`() {
        val prompt = process()
        prompt.loadedOn(days(5))
        assertFalse("a load is not a calm moment", prompt.pending.value)
        prompt.calmMoment()
        assertTrue(prompt.pending.value)
    }

    @Test
    fun `days counted by an earlier build keep counting`() {
        store.reviewPromptDaysUsed = 4
        store.reviewPromptLastDay = "2026-10-11"
        val prompt = process()
        prompt.classTableLoaded()
        prompt.calmMoment()
        assertTrue(prompt.pending.value)
        assertEquals(5, store.reviewPromptDaysUsed)
    }

    @Test
    fun `never asks again once the flow was launched`() {
        val prompt = process()
        prompt.loadedOn(days(5))
        prompt.calmMoment()
        prompt.launched(true)
        assertFalse(prompt.pending.value)
        assertTrue(store.reviewPromptAsked)

        prompt.loadedOn(days(9))
        prompt.calmMoment()
        assertFalse("asked again in the same process", prompt.pending.value)

        val later = process()
        later.loadedOn(listOf(LocalDate.of(2027, 3, 1)))
        later.calmMoment()
        assertFalse("asked again in a later process", later.pending.value)
    }

    @Test
    fun `does not ask while signed out, in the wizard, in the demo account or during a sign-in`() {
        mayAsk = false
        val prompt = process()
        prompt.loadedOn(days(5))
        prompt.calmMoment()
        assertFalse(prompt.pending.value)

        mayAsk = true
        prompt.calmMoment()
        assertTrue(prompt.pending.value)
    }

    @Test
    fun `does not ask right after a load failed`() {
        val prompt = process()
        prompt.loadedOn(days(5))
        prompt.loadFailed()
        prompt.calmMoment()
        assertFalse(prompt.pending.value)

        prompt.classTableLoaded()
        prompt.calmMoment()
        assertTrue(prompt.pending.value)
    }

    @Test
    fun `a failure withdraws an ask not yet launched`() {
        val prompt = process()
        prompt.loadedOn(days(5))
        prompt.calmMoment()
        assertTrue(prompt.pending.value)
        prompt.loadFailed()
        assertFalse(prompt.pending.value)
    }

    @Test
    fun `does not ask before the class table has loaded in this process`() {
        store.reviewPromptDaysUsed = 5
        store.reviewPromptLastDay = "2026-10-05"
        val prompt = process()
        prompt.calmMoment()
        assertFalse("asked on a cold start, before content showed", prompt.pending.value)

        prompt.classTableLoaded()
        prompt.calmMoment()
        assertTrue(prompt.pending.value)
    }

    @Test
    fun `tries once per process when Play would not hand out the flow`() {
        val prompt = process()
        prompt.loadedOn(days(5))
        prompt.calmMoment()
        prompt.launched(false)
        assertFalse(store.reviewPromptAsked)
        prompt.calmMoment()
        assertFalse("tried again in the same process", prompt.pending.value)

        val later = process()
        later.classTableLoaded()
        later.calmMoment()
        assertTrue("not tried again in a later process", later.pending.value)
    }
}
