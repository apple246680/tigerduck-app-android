package org.ntust.app.tigerduck.push

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.ntust.app.tigerduck.auth.DepartingRefresh
import org.ntust.app.tigerduck.auth.DepartingSession
import org.ntust.app.tigerduck.push.DepartingDeviceUnregister.Outcome
import java.io.IOException

/**
 * The sign-out's device unregister, which goes out as the departing account and is kept until the
 * server has it: signed out offline, or with an access token that has expired, the device stayed
 * registered to the account and went on getting its reminders.
 */
class DepartingDeviceUnregisterTest {

    private val now = 1_800_000_000_000L

    private class MemoryStore : PendingUnregisterStore {
        var pending: PendingUnregister? = null
        override fun load() = pending
        override fun save(pending: PendingUnregister) {
            this.pending = pending
        }
        override fun clear() {
            pending = null
        }
    }

    private val store = MemoryStore()
    private val owed = OwedUnregister(store)

    /** Holds the next DELETE until the test completes it with a status. */
    private var heldDelete: CompletableDeferred<Int>? = null

    /** Each DELETE as "device token". */
    private val deletes = mutableListOf<String>()
    private val deleteReplies = ArrayDeque<Any>()

    /** The refresh tokens sent. */
    private val refreshes = mutableListOf<String>()
    private val refreshReplies = ArrayDeque<DepartingRefresh>()
    private var signedInSince = false

    /** Runs as each refresh is sent, before its reply. */
    private var onRefresh: () -> Unit = {}

    /** The backend the API endpoint setting names now. */
    private var endpoint = BACKEND

    private fun unregister() = DepartingDeviceUnregister(
        owed = owed,
        deleteDevice = { base, device, token ->
            assertEquals("the DELETE goes to the backend that issued the token", BACKEND, base)
            deletes += "$device $token"
            heldDelete?.let { held -> heldDelete = null; return@DepartingDeviceUnregister held.await() }
            when (val reply = deleteReplies.removeFirstOrNull() ?: 204) {
                is IOException -> throw reply
                else -> reply as Int
            }
        },
        refresh = { session ->
            assertEquals("the refresh goes to the backend that issued the token", BACKEND, session.baseUrl)
            refreshes += session.refreshToken
            onRefresh()
            refreshReplies.removeFirstOrNull() ?: DepartingRefresh.Refused
        },
        hasSession = { signedInSince },
        currentBaseUrl = { endpoint },
        now = { now },
    )

    private fun session(access: String, refresh: String, expiresAt: Long) =
        DepartingSession(access, refresh, expiresAt, BACKEND)

    private fun refreshed(access: String, refresh: String) =
        DepartingRefresh.Refreshed(session(access, refresh, now + 900_000))

    private suspend fun owe(expiresAt: Long = now + 600_000) =
        unregister().owe(PendingUnregister("device-1", session("access-1", "refresh-1", expiresAt)))

    @Test
    fun `a token that lasts goes out as it is, and the debt ends with the reply`() = runTest {
        owe()

        assertEquals(Outcome.DONE, unregister().send())

        assertEquals(listOf("device-1 access-1"), deletes)
        assertEquals(emptyList<String>(), refreshes)
        assertNull(store.pending)
    }

    @Test
    fun `an expired token is refreshed first, and the rotated pair is kept`() = runTest {
        owe(expiresAt = now - 1)
        refreshReplies += refreshed("access-2", "refresh-2")
        deleteReplies += 503

        assertEquals(Outcome.RETRY, unregister().send())

        assertEquals(listOf("refresh-1"), refreshes)
        assertEquals(listOf("device-1 access-2"), deletes)
        // The retry sends the rotated refresh token, never the one the server replaced.
        assertEquals("refresh-2", store.pending?.session?.refreshToken)
    }

    @Test
    fun `signed out offline, it is kept and goes out on a later try`() = runTest {
        owe()
        deleteReplies += IOException("offline")

        assertEquals(Outcome.RETRY, unregister().send())
        assertEquals("device-1", store.pending?.deviceId)

        assertEquals(Outcome.DONE, unregister().send())
        assertEquals(listOf("device-1 access-1", "device-1 access-1"), deletes)
        assertNull(store.pending)
    }

    @Test
    fun `a busy server keeps it, a device it no longer has ends it`() = runTest {
        owe()
        deleteReplies += 429
        assertEquals(Outcome.RETRY, unregister().send())

        deleteReplies += 404
        assertEquals(Outcome.DONE, unregister().send())
        assertNull(store.pending)
    }

    @Test
    fun `a token turned down before its time is refreshed once and sent again`() = runTest {
        owe()
        deleteReplies += 401
        refreshReplies += refreshed("access-2", "refresh-2")

        assertEquals(Outcome.DONE, unregister().send())

        assertEquals(listOf("device-1 access-1", "device-1 access-2"), deletes)
        assertNull(store.pending)
    }

    @Test
    fun `a refresh the server turns down ends it, as nothing can go out as the account any more`() = runTest {
        owe(expiresAt = now - 1)
        refreshReplies += DepartingRefresh.Refused

        assertEquals(Outcome.DONE, unregister().send())

        assertEquals(emptyList<String>(), deletes)
        assertNull(store.pending)
    }

    @Test
    fun `a refresh that could not be sent keeps it`() = runTest {
        owe(expiresAt = now - 1)
        refreshReplies += DepartingRefresh.Unavailable

        assertEquals(Outcome.RETRY, unregister().send())

        assertEquals(emptyList<String>(), deletes)
        assertEquals("refresh-1", store.pending?.session?.refreshToken)
    }

    @Test
    fun `a sign-in since ends it unsent`() = runTest {
        // Sent as the same account, it would delete the new session's device and revoke it.
        owe()
        signedInSince = true

        assertEquals(Outcome.DONE, unregister().send())

        assertEquals(emptyList<String>(), deletes)
        assertNull(store.pending)
    }

    @Test
    fun `nothing owed sends nothing`() = runTest {
        assertEquals(Outcome.DONE, unregister().send())

        assertEquals(emptyList<String>(), deletes)
    }

    // The API endpoint setting, which the next person on the phone can change

    @Test
    fun `a debt owed to one backend is dropped unsent once the endpoint names another`() = runTest {
        owe(expiresAt = now - 1)
        endpoint = "https://elsewhere.example/v3"

        assertEquals(Outcome.DONE, unregister().send())

        assertEquals(emptyList<String>(), refreshes)
        assertEquals(emptyList<String>(), deletes)
        assertNull(store.pending)
    }

    @Test
    fun `changing the endpoint drops the debt at once, and keeping it does not`() = runTest {
        owe()
        unregister().dropIfEndpointMoved()
        assertEquals("device-1", store.pending?.deviceId)

        endpoint = "https://elsewhere.example/v3"
        unregister().dropIfEndpointMoved()
        assertNull(store.pending)
    }

    // A sign-in of the same account while the unregister is on its way

    @Test
    fun `a sign-in waits for an unregister on its way, then ends the debt`() = runTest {
        owe()
        val held = CompletableDeferred<Int>().also { heldDelete = it }
        val events = mutableListOf<String>()
        val sending = async { unregister().send().also { events += "unregister $it" } }
        runCurrent()
        val signingIn = async { owed.signingIn { events += "login" } }
        runCurrent()
        assertEquals("the login waits for the DELETE", emptyList<String>(), events)

        held.complete(503)
        sending.await()
        signingIn.await()

        assertEquals(listOf("unregister RETRY", "login"), events)
        assertNull("signed in, the device is the new session's", store.pending)
    }

    @Test
    fun `a sign-in that fails leaves the debt`() = runTest {
        owe()

        runCatching { owed.signingIn { throw IllegalStateException("login refused") } }

        assertEquals("device-1", store.pending?.deviceId)
    }

    @Test
    fun `a session that appears while the token is refreshed stops the DELETE`() = runTest {
        owe(expiresAt = now - 1)
        refreshReplies += refreshed("access-2", "refresh-2")
        onRefresh = { signedInSince = true }

        assertEquals(Outcome.DONE, unregister().send())

        assertEquals(emptyList<String>(), deletes)
        assertNull(store.pending)
    }

    private companion object {
        const val BACKEND = "https://backend.example/v3"
    }
}
