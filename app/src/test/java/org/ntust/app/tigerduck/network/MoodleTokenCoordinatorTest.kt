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
import org.ntust.app.tigerduck.auth.InMemoryStoredPasswordLatch

/** How MoodleTokenService gets, renews and stores the token around its SSO sign-in. */
@OptIn(ExperimentalCoroutinesApi::class)
class MoodleTokenCoordinatorTest {

    private class Tokens : MoodleTokenCoordinator.Tokens {
        override var token: String? = null
        override var studentId: String? = "B11300001"
        override var password: String? = "pw"
        override fun storeIfSignedIn(studentId: String, token: String): Boolean {
            if (this.studentId != studentId) return false
            this.token = token
            return true
        }
    }

    private val tokens = Tokens()
    private val latch = InMemoryStoredPasswordLatch()
    private var now = 1_000_000L
    private val logins = mutableListOf<String>()
    private var loginGate: CompletableDeferred<Unit>? = null
    private var login: suspend (String, String) -> String = { id, _ -> "token-$id-${logins.size}" }

    private fun kotlinx.coroutines.test.TestScope.coordinator() = MoodleTokenCoordinator(
        tokens = tokens,
        login = { id, password, posting ->
            logins += id
            loginGate?.await()
            posting.post { login(id, password) }
        },
        scope = backgroundScope,
        now = { now },
        pauseMs = 300_000L,
        passwordLatch = latch,
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
        val other = MoodleTokenCoordinator(tokens, { id, _, posting -> logins += id; posting.post { throw MoodleWebserviceError.SsoLoginRejected(null) } }, backgroundScope, { now }, 300_000L, InMemoryStoredPasswordLatch())
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
    fun `a refresh that reads the token a sign-in is storing takes it`() = runTest(UnconfinedTestDispatcher()) {
        lateinit var coordinator: MoodleTokenCoordinator
        var refresh: kotlinx.coroutines.Deferred<String>? = null
        val storing = object : MoodleTokenCoordinator.Tokens {
            override var token: String? = "old"
                set(value) {
                    field = value
                    // The backend sign-in starts the moment the token lands, before obtain returns.
                    if (refresh == null) refresh = backgroundScope.async { coordinator.refresh() }
                }
            override val studentId: String? = "B11300001"
            override val password: String? = "pw"
            override fun storeIfSignedIn(studentId: String, token: String) = true.also { this.token = token }
        }
        coordinator = MoodleTokenCoordinator(storing, { id, _, _ -> logins += id; "token-${logins.size}" }, backgroundScope, { now }, 300_000L, latch)

        val obtained = coordinator.obtain("B11300001", "pw")

        assertEquals(obtained, refresh!!.await())
        assertEquals(listOf("B11300001"), logins)
    }

    @Test
    fun `refusals are kept in the app-wide latch, which a sign-in by hand lifts`() = runTest(UnconfinedTestDispatcher()) {
        tokens.token = "dead"
        val first = MoodleTokenCoordinator(tokens, { id, _, posting -> logins += id; posting.post { throw MoodleWebserviceError.SsoLoginRejected("wrong") } }, backgroundScope, { now }, 300_000L, latch)
        runCatching { first.renew("dead") }
        assertEquals("wrong", latch.rejection.value?.reason)

        // Another coordinator over the same latch, as after the app restarts with the persisted one.
        val second = MoodleTokenCoordinator(tokens, { id, _, _ -> logins += id; "fresh" }, backgroundScope, { now }, 300_000L, latch)
        assertTrue(runCatching { second.refresh() }.exceptionOrNull() is MoodleWebserviceError.SsoLoginRejected)
        assertEquals(1, logins.size)

        second.obtain("B11300001", "pw")
        assertNull("a sign-in by hand lifts it", latch.rejection.value)
    }

    @Test
    fun `a refusal latched while a renewal waits to post stops it before the password goes out`() = runTest(UnconfinedTestDispatcher()) {
        tokens.token = "dead"
        var posted = false
        val coordinator = MoodleTokenCoordinator(
            tokens,
            { _, _, posting ->
                // Another sign-in's refusal lands while this one is on its way to the form.
                latch.markRejected(latch.generation, "B11300001", "pw", "Locked")
                posting.post {
                    posted = true
                    "fresh"
                }
            },
            backgroundScope, { now }, 300_000L, latch,
        )

        val error = runCatching { coordinator.renew("dead") }.exceptionOrNull()

        assertTrue("got $error", error is MoodleWebserviceError.SsoLoginRejected)
        assertTrue("nothing posted", !posted)
        assertEquals("dead", tokens.token)
    }

    @Test
    fun `a sign-in by hand posts what was typed, latch or not`() = runTest(UnconfinedTestDispatcher()) {
        latch.markRejected(latch.generation, "B11300001", "old", "Wrong")

        assertEquals("token-B11300001-1", coordinator().obtain("B11300001", "new"))
        assertNull(latch.rejection.value)
    }

    @Test
    fun `a sign-out during a sign-in by hand stores nothing of the departing account`() = runTest(UnconfinedTestDispatcher()) {
        tokens.token = null
        login = { _, _ ->
            tokens.studentId = null
            "departing"
        }

        val error = runCatching { coordinator().obtain("B11300001", "pw") }.exceptionOrNull()

        assertTrue("got $error", error is MoodleWebserviceError.MissingStoredCredentials)
        assertNull(tokens.token)
    }

    @Test
    fun `a refresh does not take a token a renewal is already replacing`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinator()
        val obtained = coordinator.obtain("B11300001", "pw")
        // Moodle answers that token is dead, and a renewal starts.
        loginGate = CompletableDeferred()
        val renewal = async { coordinator.renew(rejected = obtained) }
        runCurrent()

        val refresh = async { coordinator.refresh() }
        runCurrent()
        loginGate!!.complete(Unit)

        assertEquals(renewal.await(), refresh.await())
        assertTrue("not the dead one", refresh.await() != obtained)
        assertEquals(2, logins.size)
    }

    @Test
    fun `a token Moodle rejected is not handed out as just got, even when its renewal failed`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinator()
        val obtained = coordinator.obtain("B11300001", "pw")
        login = { _, _ -> throw MoodleWebserviceError.SiteMaintenance() }
        runCatching { coordinator.renew(rejected = obtained) }

        val error = runCatching { coordinator.refresh() }.exceptionOrNull()

        assertTrue("the renewal's failure, not the dead token: $error", error is MoodleWebserviceError.SiteMaintenance)
        assertEquals(2, logins.size)
    }

    @Test
    fun `a token just got for one account is not handed out for another`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinator()
        val first = coordinator.obtain("B11300001", "pw")
        // Another account's credentials, with the first account's token still stored.
        tokens.studentId = "B11300002"
        tokens.password = "pw2"

        val refreshed = coordinator.refresh()

        assertTrue(refreshed != first)
        assertEquals(listOf("B11300001", "B11300002"), logins)
    }

    @Test
    fun `a sign-out right after a refresh checked the account leaves no token behind`() = runTest(UnconfinedTestDispatcher()) {
        var loggedIn = false
        var signedOut = false
        val racing = object : MoodleTokenCoordinator.Tokens {
            private var id: String? = "B11300001"
            override var token: String? = null
            override var password: String? = "pw"
            // The sign-out lands the moment the account is read after the SSO answer.
            override val studentId: String? get() = id.also { if (loggedIn && !signedOut) signOut() }
            // Atomic with a sign-out, as the credential store makes it.
            override fun storeIfSignedIn(studentId: String, token: String): Boolean {
                if (id != studentId) return false
                this.token = token
                return true
            }
            fun signOut() {
                signedOut = true
                id = null
                password = null
                token = null
            }
        }
        val coordinator = MoodleTokenCoordinator(racing, { id, _, _ -> logins += id; loggedIn = true; "departing" }, backgroundScope, { now }, 300_000L, latch)

        runCatching { coordinator.refresh() }
        if (!signedOut) racing.signOut()

        assertNull("the departing account's token stays out of the store", racing.token)
    }
}
