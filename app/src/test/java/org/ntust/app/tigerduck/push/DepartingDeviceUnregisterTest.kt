package org.ntust.app.tigerduck.push

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

    /** Each DELETE as "device token". */
    private val deletes = mutableListOf<String>()
    private val deleteReplies = ArrayDeque<Any>()

    /** The refresh tokens sent. */
    private val refreshes = mutableListOf<String>()
    private val refreshReplies = ArrayDeque<DepartingRefresh>()
    private var signedInSince = false

    private fun unregister() = DepartingDeviceUnregister(
        store = store,
        deleteDevice = { device, token ->
            deletes += "$device $token"
            when (val reply = deleteReplies.removeFirstOrNull() ?: 204) {
                is IOException -> throw reply
                else -> reply as Int
            }
        },
        refresh = { token ->
            refreshes += token
            refreshReplies.removeFirstOrNull() ?: DepartingRefresh.Refused
        },
        hasSession = { signedInSince },
        now = { now },
    )

    private fun session(access: String, refresh: String, expiresAt: Long) =
        DepartingSession(access, refresh, expiresAt)

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
}
