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
        now = { 0L },
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
}
