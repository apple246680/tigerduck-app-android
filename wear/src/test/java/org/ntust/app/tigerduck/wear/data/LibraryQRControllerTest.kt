package org.ntust.app.tigerduck.wear.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.shared.LibraryServiceError
import org.robolectric.RobolectricTestRunner
import kotlin.coroutines.CoroutineContext

/**
 * The watch's code page when the phone signs the library account out or hands
 * over another one while a code is being fetched: the fetch is dropped
 * (tigerduck-app/tigerduck-app-android#293), and the page says so and starts
 * afresh for the next account instead of naming an exception until reopened.
 */
@RunWith(RobolectricTestRunner::class)
class LibraryQRControllerTest {

    private val accounts = MutableStateFlow<String?>("B10000001")
    private val answers = ArrayDeque<suspend () -> String>()

    // Unconfined, so each step below has run by the time the next line reads the state.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val controller = LibraryQRController(
        generate = { answers.removeFirst()() },
        scope = scope,
        accountChangedMessage = ACCOUNT_CHANGED,
        renderContext = Dispatchers.Unconfined,
    )

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `a code dropped as its account changed says so rather than naming the exception`() {
        answers += { throw LibraryServiceError.CredentialsNotFound() }

        controller.start(64, accounts)

        assertEquals(ACCOUNT_CHANGED, controller.error.value)
        assertNull(controller.qrBitmap.value)
    }

    @Test
    fun `another account from the phone starts the code afresh`() {
        answers += { throw LibraryServiceError.CredentialsNotFound() }
        answers += { "code" }
        controller.start(64, accounts)

        accounts.value = "B10000002"

        assertNull(controller.error.value)
        assertNotNull(controller.qrBitmap.value)
    }

    @Test
    fun `the previous account's code is gone while the next one's loads`() {
        answers += { "code" }
        answers += { awaitCancellation() }
        controller.start(64, accounts)
        assertNotNull(controller.qrBitmap.value)

        accounts.value = "B10000002"

        assertNull(controller.qrBitmap.value)
        assertTrue(controller.isLoading.value)
    }

    @Test
    fun `the same account in another case keeps its code while it reloads`() {
        answers += { "code" }
        answers += { awaitCancellation() }
        controller.start(64, accounts)

        accounts.value = "b10000001"

        assertNotNull(controller.qrBitmap.value)
    }

    /** Runs what is dispatched to it only when the test says, in order. */
    private class Queue : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks += block
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    /**
     * A restart, as toggling full screen makes, while the previous code still
     * renders: the previous fetch's cleanup, once its render is done, must not
     * mark the new fetch as finished.
     */
    @Test
    fun `a restart while the previous code renders stays loading until its own code is in`() {
        val main = Queue()
        val render = Queue()
        val queued = CoroutineScope(SupervisorJob() + main)
        val controller = LibraryQRController({ answers.removeFirst()() }, queued, ACCOUNT_CHANGED, render)
        answers += { "code" }
        answers += { awaitCancellation() }

        controller.start(64, accounts)
        main.runAll()
        controller.start(128, accounts)
        main.runAll()
        render.runAll()
        main.runAll()

        assertTrue(controller.isLoading.value)
        queued.cancel()
    }

    private companion object {
        const val ACCOUNT_CHANGED = "Open TigerDuck on your phone to sync."
    }
}
