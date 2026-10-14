package org.ntust.app.tigerduck.ui.screen.library

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.library.LibraryQRCache
import org.ntust.app.tigerduck.shared.LibraryServiceError
import java.io.IOException

/**
 * The Library page's pass, as the view model runs it: the code cycle, the
 * page's own failures and the drop to the sign-in form when the shared
 * sign-in state turns signed out (tigerduck-app/tigerduck-app#556).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryPassStateTest {

    /** Whether the stores still hold an account the page can show the pass for. */
    private var hasAccount = true

    /** The library sign-in state Settings and the flip follow. */
    private val signedIn = MutableStateFlow(true)

    /** What each code request does; past the end the library answers codes. */
    private val fetches = ArrayDeque<suspend () -> String>()
    private var fetched = 0

    /** Whether the page showed the pass each time it read the session again. */
    private val refreshedWhileLoggedIn = mutableListOf<Boolean>()

    private lateinit var state: LibraryPassState<String>

    private fun TestScope.pass(): LibraryPassState<String> = LibraryPassState(
        scope = backgroundScope,
        hasAccount = { hasAccount },
        codeAccount = { 1 },
        fetch = {
            fetched++
            fetches.removeFirstOrNull()?.invoke() ?: "code-$fetched"
        },
        render = { "image of $it" },
        signedIn = signedIn,
        refreshSession = {
            refreshedWhileLoggedIn += state.isLoggedIn.value
            signedIn.value = hasAccount
        },
        describe = { it.message ?: "failed" },
        cache = LibraryQRCache(),
        now = { testScheduler.currentTime },
    ).also { state = it }

    /**
     * The page's own code request finds the refusal: the sign-in it waits on records it, and
     * the app reads the session again, before the page hears of the failure. The page still
     * says what the library said.
     */
    @Test
    fun `the page's own refusal keeps the library's words when the sign-out it causes arrives first`() = runTest {
        fetches += {
            hasAccount = false
            signedIn.value = false
            yield()
            throw LibraryServiceError.LoginFailed("wrong password")
        }
        val pass = pass()

        pass.load()
        runCurrent()

        assertFalse(pass.isLoggedIn.value)
        assertEquals("wrong password", pass.errors.value)
        assertNull(pass.image.value)
    }

    /** It shows the sign-in form before it reads the session again, so that read drops nothing more. */
    @Test
    fun `the page's own refusal shows the form, then reads the session again`() = runTest {
        fetches += {
            hasAccount = false
            throw LibraryServiceError.LoginFailed("wrong password")
        }
        val pass = pass()

        pass.load()
        runCurrent()

        assertEquals(listOf(false), refreshedWhileLoggedIn)
        assertEquals("wrong password", pass.errors.value)
    }

    @Test
    fun `a sign-out found elsewhere drops the page while it backs off, and its old error with it`() = runTest {
        fetches += { throw IOException("timed out") }
        val pass = pass()
        pass.load()
        runCurrent()
        assertTrue(pass.isLoggedIn.value)
        assertEquals("timed out", pass.errors.value)

        hasAccount = false
        signedIn.value = false
        runCurrent()

        assertFalse(pass.isLoggedIn.value)
        assertNull(pass.errors.value)
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(1, fetched)
    }

    /** The form's own failure, such as a password typed wrong, stays when the session reads signed out. */
    @Test
    fun `a page already on the sign-in form keeps what it says`() = runTest {
        hasAccount = false
        val pass = pass()
        pass.load()
        runCurrent()
        pass.errors.value = "wrong password"

        signedIn.value = false
        runCurrent()

        assertEquals("wrong password", pass.errors.value)
        assertEquals(0, fetched)
    }

    @Test
    fun `a signed-out the stores no longer say drops nothing`() = runTest {
        val pass = pass()
        pass.load()
        runCurrent()

        signedIn.value = false
        runCurrent()

        assertTrue(pass.isLoggedIn.value)
        assertEquals("image of code-1", pass.image.value)
    }
}
