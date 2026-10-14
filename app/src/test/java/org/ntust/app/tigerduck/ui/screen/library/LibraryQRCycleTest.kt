package org.ntust.app.tigerduck.ui.screen.library

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.library.LibraryQRCache
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryQRCycleTest {

    /** What the library answers, one entry per request; past the end it keeps answering codes. */
    private val answers = ArrayDeque<Result<String>>()
    private val fetchedAt = mutableListOf<Long>()
    private var account = 1
    private var sessionValid = true
    private var sessionEnded = 0
    private val errors = MutableStateFlow<String?>(null)
    private var codes = 0

    private fun TestScope.cycle(): LibraryQRCycle<String> = LibraryQRCycle(
        scope = backgroundScope,
        fetch = {
            fetchedAt += testScheduler.currentTime
            val answer = answers.removeFirstOrNull() ?: Result.success("code-${++codes}")
            answer.getOrThrow()
        },
        render = {
            renders++
            "image of $it"
        },
        account = { account },
        sessionValid = { sessionValid },
        onSessionEnded = { sessionEnded++ },
        describe = { it.message ?: "failed" },
        errors = errors,
        cache = cache,
        now = { testScheduler.currentTime },
    )

    private val cache = LibraryQRCache<String>()
    private var renders = 0

    private fun fail() = Result.failure<String>(IOException("HTTP 503"))

    @Test
    fun `a code is shown with its countdown and replaced when that runs out`() = runTest {
        val cycle = cycle()
        cycle.start()
        runCurrent()
        assertEquals("image of code-1", cycle.image.value)
        assertEquals(30, cycle.countdown.value)

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals("image of code-2", cycle.image.value)
        assertEquals(listOf(0L, 30_000L), fetchedAt)
    }

    @Test
    fun `a failed refresh is tried again after 60, 120 then every 300 seconds`() = runTest {
        answers += listOf(Result.success("first"), fail(), fail(), fail(), fail())
        val cycle = cycle()
        cycle.start()
        advanceTimeBy(30_000 + 60_000 + 120_000 + 300_000 + 300_000)
        runCurrent()

        assertEquals(listOf(0L, 30_000L, 90_000L, 210_000L, 510_000L, 810_000L), fetchedAt)
    }

    @Test
    fun `the expired code goes off the screen when its refresh fails`() = runTest {
        answers += listOf(Result.success("first"), fail())
        val cycle = cycle()
        cycle.start()
        advanceTimeBy(30_000)
        runCurrent()

        assertNull(cycle.image.value)
        assertEquals(0, cycle.countdown.value)
        assertEquals("HTTP 503", errors.value)
        assertEquals(0, sessionEnded)
    }

    @Test
    fun `a refresh that recovers goes back to a code every 30 seconds`() = runTest {
        answers += listOf(Result.success("first"), fail())
        val cycle = cycle()
        cycle.start()
        advanceTimeBy(30_000 + 60_000)
        runCurrent()
        assertEquals("image of code-1", cycle.image.value)
        assertNull(errors.value)

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(listOf(0L, 30_000L, 90_000L, 120_000L), fetchedAt)
    }

    @Test
    fun `after a recovery the next failure waits 60 seconds again`() = runTest {
        answers += listOf(Result.success("first"), fail(), fail(), Result.success("back"), fail())
        val cycle = cycle()
        cycle.start()
        // first at 0, fails at 30 and 90, back at 210, fails at 240, retried at 300.
        advanceTimeBy(300_000)
        runCurrent()

        assertEquals(listOf(0L, 30_000L, 90_000L, 210_000L, 240_000L, 300_000L), fetchedAt)
    }

    @Test
    fun `a failure with the session gone stops and hands back to the sign-in`() = runTest {
        answers += listOf(Result.success("first"), fail())
        val cycle = cycle()
        cycle.start()
        runCurrent()
        sessionValid = false
        advanceTimeBy(30_000 + 600_000)
        runCurrent()

        assertEquals(1, sessionEnded)
        assertNull(cycle.image.value)
        assertEquals(listOf(0L, 30_000L), fetchedAt)
    }

    @Test
    fun `stopping cancels a retry that is waiting`() = runTest {
        answers += listOf(Result.success("first"), fail())
        val cycle = cycle()
        cycle.start()
        advanceTimeBy(30_000)
        runCurrent()
        cycle.stop()
        advanceTimeBy(600_000)
        runCurrent()

        assertEquals(listOf(0L, 30_000L), fetchedAt)
    }

    @Test
    fun `a code still valid when a fetch fails goes the moment it runs out`() = runTest {
        val cycle = cycle()
        cycle.start()
        runCurrent()
        cycle.stop()
        // Too little left to show again, so the page asks for a new one.
        advanceTimeBy(20_000)
        answers += fail()
        cycle.start()
        runCurrent()
        assertEquals("image of code-1", cycle.image.value)

        advanceTimeBy(9_000)
        runCurrent()
        assertEquals("image of code-1", cycle.image.value)
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(cycle.image.value)
    }

    @Test
    fun `a code that arrives for an account that left is not shown`() = runTest {
        val cycle = LibraryQRCycle(
            scope = backgroundScope,
            fetch = {
                account = 2
                "previous account's code"
            },
            render = { "image of $it" },
            account = { account },
            sessionValid = { true },
            onSessionEnded = {},
            describe = { "failed" },
            errors = errors,
            cache = cache,
            now = { testScheduler.currentTime },
        )
        cycle.start()
        runCurrent()

        assertNull(cycle.image.value)
    }

    @Test
    fun `a code left from another account is taken off before the next fetch`() = runTest {
        val cycle = cycle()
        cycle.start()
        runCurrent()
        cycle.stop()
        account = 2
        answers += fail()
        cycle.start()
        runCurrent()

        assertNull(cycle.image.value)
        assertFalse(cycle.isLoading.value)
    }

    @Test
    fun `the backoff holds at five minutes`() {
        assertEquals(60, LibraryQRCycle.retryDelaySeconds(1))
        assertEquals(120, LibraryQRCycle.retryDelaySeconds(2))
        assertEquals(300, LibraryQRCycle.retryDelaySeconds(3))
        assertEquals(300, LibraryQRCycle.retryDelaySeconds(9))
        assertTrue(LibraryQRCycle.retryDelaySeconds(0) > 0)
    }

    @Test
    fun `coming back within the code's lifetime shows the same code at once and resumes its countdown`() = runTest {
        val first = cycle()
        first.start()
        runCurrent()
        first.stop()
        advanceTimeBy(12_000)

        // A new page, as after leaving Library and coming back.
        val second = cycle()
        second.start()
        runCurrent()
        assertEquals("image of code-1", second.image.value)
        assertEquals(18, second.countdown.value)
        assertEquals(listOf(0L), fetchedAt)
        assertEquals(1, renders)

        advanceTimeBy(18_000)
        runCurrent()
        assertEquals("image of code-2", second.image.value)
        assertEquals(listOf(0L, 30_000L), fetchedAt)
    }

    @Test
    fun `with less than 15 seconds left a new code is fetched`() = runTest {
        val first = cycle()
        first.start()
        runCurrent()
        first.stop()
        advanceTimeBy(16_000)

        val second = cycle()
        second.start()
        runCurrent()
        assertEquals("image of code-2", second.image.value)
        assertEquals(listOf(0L, 16_000L), fetchedAt)
    }

    @Test
    fun `a kept code is not shown for another account`() = runTest {
        val first = cycle()
        first.start()
        runCurrent()
        first.stop()
        account = 2

        val second = cycle()
        second.start()
        runCurrent()
        assertEquals("image of code-2", second.image.value)
        assertEquals(2, fetchedAt.size)
    }

    @Test
    fun `an image dropped under memory pressure is rendered again from the kept code`() = runTest {
        val first = cycle()
        first.start()
        runCurrent()
        first.stop()
        cache.dropImage()

        val second = cycle()
        second.start()
        runCurrent()
        assertEquals("image of code-1", second.image.value)
        assertEquals(1, fetchedAt.size)
        assertEquals(2, renders)
    }

    @Test
    fun `a session that ends leaves no code to show again`() = runTest {
        answers += listOf(Result.success("first"), fail())
        val cycle = cycle()
        cycle.start()
        runCurrent()
        sessionValid = false
        advanceTimeBy(30_000)
        runCurrent()

        assertNull(cache.reusable(account, testScheduler.currentTime))
    }

    /**
     * A refusal another caller found, such as the sign-in at a return to the app, signs Settings
     * and the flip out at once; the page drops to the sign-in form then too, not at its next try
     * after a backoff of up to five minutes (tigerduck-app/tigerduck-app#556).
     */
    @Test
    fun `a sign-out found elsewhere drops the page while its code request backs off`() = runTest {
        answers += fail()
        val cycle = cycle()
        val signedIn = MutableStateFlow(true)
        var signedOut = 0
        backgroundScope.followSignOuts(signedIn, stillSignedIn = { sessionValid }) {
            signedOut++
            cycle.clear()
        }
        cycle.start()
        runCurrent()
        assertEquals("HTTP 503", errors.value)

        sessionValid = false
        signedIn.value = false
        runCurrent()

        assertEquals(1, signedOut)
        assertNull(cycle.image.value)
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(listOf(0L), fetchedAt)
    }

    /** The stores are read again, so a signed-out read before an account was stored drops nothing. */
    @Test
    fun `a signed-out that the stores no longer say drops nothing`() = runTest {
        val signedIn = MutableStateFlow(true)
        var signedOut = 0
        backgroundScope.followSignOuts(signedIn, stillSignedIn = { sessionValid }) { signedOut++ }
        runCurrent()

        signedIn.value = false
        runCurrent()

        assertEquals(0, signedOut)
    }
}
