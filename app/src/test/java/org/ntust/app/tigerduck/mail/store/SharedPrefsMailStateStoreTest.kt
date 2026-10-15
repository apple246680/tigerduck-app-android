package org.ntust.app.tigerduck.mail.store

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SharedPrefsMailStateStoreTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `each folder's date outlives the process and goes with the rest at sign-out`() {
        SharedPrefsMailStateStore(context).apply {
            setFetchedAt("INBOX", 5_000L)
            setFetchedAt("寄件備份匣", 7_000L)
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
}
