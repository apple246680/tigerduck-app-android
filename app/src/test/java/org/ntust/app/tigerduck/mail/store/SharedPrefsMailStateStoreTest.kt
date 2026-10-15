package org.ntust.app.tigerduck.mail.store

import android.app.Application
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
    fun `the inbox's date outlives the process and goes with the rest at sign-out`() {
        SharedPrefsMailStateStore(context).inboxFetchedAt = 5_000L

        val reopened = SharedPrefsMailStateStore(context)
        assertEquals(5_000L, reopened.inboxFetchedAt)

        reopened.clear()
        assertEquals(0L, SharedPrefsMailStateStore(context).inboxFetchedAt)
    }
}
