package org.ntust.app.tigerduck.mail.store

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SharedPrefsMailStateStoreTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `each folder's date outlives the process and goes with the rest at sign-out`() {
        SharedPrefsMailStateStore(context).apply {
            assertTrue(setFetchedAtIf("INBOX", 5_000L) { true })
            assertTrue(setFetchedAtIf("寄件備份匣", 7_000L) { true })
        }

        val reopened = SharedPrefsMailStateStore(context)
        assertEquals(5_000L, reopened.fetchedAt("INBOX"))
        assertEquals(7_000L, reopened.fetchedAt("寄件備份匣"))
        assertEquals(0L, reopened.fetchedAt("草稿匣"))

        reopened.clear()
        val cleared = SharedPrefsMailStateStore(context)
        assertEquals(0L, cleared.fetchedAt("INBOX"))
        assertEquals(0L, cleared.fetchedAt("寄件備份匣"))
    }

    /**
     * The builds before this one dated the inbox alone, under one key. An install upgraded from
     * one of them opens on the inbox's date rather than on "never synced".
     */
    @Test
    fun `the inbox's date is read from the key the one-folder builds wrote`() {
        context.getSharedPreferences("school_mail_state", Context.MODE_PRIVATE)
            .edit().putLong("inbox_fetched_at", 5_000L).commit()

        val store = SharedPrefsMailStateStore(context)
        assertEquals(5_000L, store.fetchedAt("INBOX"))
        // IMAP names the inbox INBOX in any case.
        assertEquals(5_000L, store.fetchedAt("Inbox"))
        assertEquals(0L, store.fetchedAt("寄件備份匣"))
    }

    @Test
    fun `a date refused by its check is not written`() {
        val store = SharedPrefsMailStateStore(context)
        assertFalse(store.setFetchedAtIf("INBOX", 5_000L) { false })
        assertEquals(0L, store.fetchedAt("INBOX"))
    }

    /**
     * The date's check and write share the store's lock, which the sign-out's clear takes too: a
     * clear that comes while a write is asking whether it may waits, then removes what it let
     * through. Without that, a date allowed just before the sign-out could land after the clear.
     */
    @Test
    fun `the sign-out's clear waits for a date that is asking whether to write`() {
        val store = SharedPrefsMailStateStore(context)
        val asking = CountDownLatch(1)
        val answer = CountDownLatch(1)
        val writing = thread {
            store.setFetchedAtIf("INBOX", 5_000L) {
                asking.countDown()
                answer.await(5, TimeUnit.SECONDS)
                true
            }
        }
        assertTrue(asking.await(5, TimeUnit.SECONDS))
        val clearing = thread { store.clear() }
        // Time for a clear that does not wait to run; one that waits for the lock cannot.
        Thread.sleep(200)
        answer.countDown()
        writing.join()
        clearing.join()

        assertEquals(0L, store.fetchedAt("INBOX"))
    }
}
