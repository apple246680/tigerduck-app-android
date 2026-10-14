package org.ntust.app.tigerduck.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How MoodleTokenService gets, renews and stores the token around its SSO sign-in. */
@OptIn(ExperimentalCoroutinesApi::class)
class MoodleTokenCoordinatorTest {

    private class Tokens : MoodleTokenCoordinator.Tokens {
        override var token: String? = null
        override var studentId: String? = "B11300001"
        override var password: String? = "pw"
    }

    private val tokens = Tokens()
    private var now = 1_000_000L
    private val logins = mutableListOf<String>()
    private var loginGate: CompletableDeferred<Unit>? = null
    private var login: suspend (String, String) -> String = { id, _ -> "token-$id-${logins.size}" }

    private fun kotlinx.coroutines.test.TestScope.coordinator() = MoodleTokenCoordinator(
        tokens = tokens,
        login = { id, password ->
            logins += id
            loginGate?.await()
            login(id, password)
        },
        scope = backgroundScope,
        now = { now },
        pauseMs = 300_000L,
    )

    @Test
    fun `a renewal waiting behind the sign-in takes the token it got, with no second sign-in`() = runTest(UnconfinedTestDispatcher()) {
        loginGate = CompletableDeferred()
        val coordinator = coordinator()
        // The sign-in harvests a token; a Moodle call finds none yet and asks for one meanwhile.
        val signIn = async { coordinator.obtain("b11300001", "pw") }
        runCurrent()
        val renewal = async { coordinator.renew(rejected = null) }
        runCurrent()
        loginGate!!.complete(Unit)

        assertEquals(signIn.await(), renewal.await())
        assertEquals(listOf("B11300001"), logins)
    }

    @Test
    fun `a renewal stores the token it got`() = runTest(UnconfinedTestDispatcher()) {
        tokens.token = "dead"
        val fresh = coordinator().renew(rejected = "dead")

        assertEquals(fresh, tokens.token)
        assertEquals(1, logins.size)
    }

    @Test
    fun `a sign-in by hand lets renewals try again after a failure`() = runTest(UnconfinedTestDispatcher()) {
        tokens.token = "dead"
        val coordinator = coordinator()
        login = { _, _ -> throw MoodleWebserviceError.SsoLoginRejected("wrong password") }
        runCatching { coordinator.renew("dead") }
        assertTrue(runCatching { coordinator.renew("dead") }.exceptionOrNull() is MoodleWebserviceError.SsoLoginRejected)
        assertEquals("refused credentials are not sent twice", 1, logins.size)

        login = { _, _ -> "fresh" }
        coordinator.obtain("B11300001", "pw")
        tokens.token = "dead-again"

        assertEquals("fresh", coordinator.renew("dead-again"))
        assertEquals(3, logins.size)
    }

    @Test
    fun `a token harvested for an account that signed out meanwhile is not kept`() = runTest(UnconfinedTestDispatcher()) {
        tokens.token = "dead"
        login = { _, _ ->
            tokens.studentId = null
            "departing"
        }

        val error = runCatching { coordinator().renew("dead") }.exceptionOrNull()

        assertTrue(error is MoodleWebserviceError.MissingStoredCredentials)
        assertEquals("dead", tokens.token)
        assertNull(tokens.studentId)
    }

    @Test
    fun `a refresh with a password SSO refused sends nothing, and its own refusal counts`() = runTest(UnconfinedTestDispatcher()) {
        tokens.token = "dead"
        val coordinator = coordinator()
        login = { _, _ -> throw MoodleWebserviceError.SsoLoginRejected("wrong password") }
        runCatching { coordinator.renew("dead") }

        assertTrue(runCatching { coordinator.refresh() }.exceptionOrNull() is MoodleWebserviceError.SsoLoginRejected)
        assertEquals("the refused password is not sent again", 1, logins.size)

        // And the other way round: a refresh SSO refuses stops the renewals after it.
        val other = MoodleTokenCoordinator(tokens, { id, _ -> logins += id; throw MoodleWebserviceError.SsoLoginRejected(null) }, backgroundScope, { now }, 300_000L)
        runCatching { other.refresh() }
        runCatching { other.refresh() }
        runCatching { other.renew("dead") }
        assertEquals(2, logins.size)
    }

    @Test
    fun `a refresh is paused after a failed sign-in, as a renewal is`() = runTest(UnconfinedTestDispatcher()) {
        tokens.token = "dead"
        val coordinator = coordinator()
        login = { _, _ -> throw MoodleWebserviceError.SiteMaintenance() }
        runCatching { coordinator.refresh() }

        assertTrue(runCatching { coordinator.refresh() }.exceptionOrNull() is MoodleWebserviceError.SiteMaintenance)
        assertEquals(1, logins.size)

        now += 300_000L
        login = { _, _ -> "fresh" }
        assertEquals("fresh", coordinator.refresh())
        assertEquals("the pause over, it signs in", 2, logins.size)
    }

    @Test
    fun `a refresh right after a token was harvested takes that token`() = runTest(UnconfinedTestDispatcher()) {
        tokens.token = "dead"
        val coordinator = coordinator()
        val renewed = coordinator.renew("dead")

        assertEquals(renewed, coordinator.refresh())
        assertEquals("no second sign-in", 1, logins.size)

        val obtained = coordinator.obtain("B11300001", "pw")
        assertEquals(obtained, coordinator.refresh())
        assertEquals(2, logins.size)

        now += 300_000L
        val later = coordinator.refresh()
        assertEquals("an older token is harvested afresh", 3, logins.size)
        assertEquals(later, tokens.token)
    }

    @Test
    fun `a refresh waiting behind the sign-in takes the token it got`() = runTest(UnconfinedTestDispatcher()) {
        tokens.token = "old"
        loginGate = CompletableDeferred()
        val coordinator = coordinator()
        val signIn = async { coordinator.obtain("b11300001", "pw") }
        runCurrent()
        val refresh = async { coordinator.refresh() }
        runCurrent()
        loginGate!!.complete(Unit)

        assertEquals(signIn.await(), refresh.await())
        assertEquals(listOf("B11300001"), logins)
    }

    @Test
    fun `refusals are kept where the coordinator is told to keep them`() = runTest(UnconfinedTestDispatcher()) {
        tokens.token = "dead"
        val refusal = object : StoredPasswordRefusal {
            var refused = mutableSetOf<String>()
            override fun isRefused(fingerprint: String) = fingerprint in refused
            override fun markRefused(fingerprint: String) { refused += fingerprint }
            override fun clear() = refused.clear()
        }
        val first = MoodleTokenCoordinator(tokens, { id, _ -> logins += id; throw MoodleWebserviceError.SsoLoginRejected(null) }, backgroundScope, { now }, 300_000L, refusal)
        runCatching { first.renew("dead") }
        assertEquals(1, refusal.refused.size)

        // Another coordinator over the same record, as after the app restarts with a persisted one.
        val second = MoodleTokenCoordinator(tokens, { id, _ -> logins += id; "fresh" }, backgroundScope, { now }, 300_000L, refusal)
        assertTrue(runCatching { second.refresh() }.exceptionOrNull() is MoodleWebserviceError.SsoLoginRejected)
        assertEquals(1, logins.size)

        second.obtain("B11300001", "pw")
        assertTrue("a sign-in by hand clears it", refusal.refused.isEmpty())
    }
}
