package org.ntust.app.tigerduck.push

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.auth.DepartingRefresh
import org.ntust.app.tigerduck.auth.DepartingSession
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.util.logSummary
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** The device unregister a sign-out still owes the backend, with the session it goes out as. */
class PendingUnregister(
    val deviceId: String,
    val session: DepartingSession,
    /** When the sign-out owed it, epoch ms. */
    val owedAt: Long,
    /**
     * How many sends of [session]'s refresh token may have reached the backend without a pair
     * coming back (no reply, or a 5xx), raised on disk before each goes out: kept across
     * launches, so a process ended during a send cannot lead to a third. Only a send the backend
     * certainly did not use ([DepartingRefresh.Unused]) is taken off again.
     */
    val unansweredRefreshes: Int = 0,
) {
    fun with(session: DepartingSession = this.session, unansweredRefreshes: Int = this.unansweredRefreshes) =
        PendingUnregister(deviceId, session, owedAt, unansweredRefreshes)
}

/** Keeps the owed unregister between launches. It holds tokens, so it is stored encrypted. */
interface PendingUnregisterStore {
    fun load(): PendingUnregister?

    /** Says whether the disk took it: a send counted in memory only is not made. */
    fun save(pending: PendingUnregister): Boolean
    fun clear()
}

/**
 * Sends the device unregister (`DELETE /devices/{id}`) a sign-out owes, as the account that
 * signed out, until the server has it. The server keeps a device registered to the account
 * otherwise, and goes on sending that account's reminders to the phone.
 *
 * It used to go out once, fire and forget, with the access token as it stood at sign-out: an
 * expired one was turned down, and offline nothing went out at all. Now the unregister is kept
 * with the departing session until it lands; an access token that will not last is refreshed
 * first, and the pair that brings is kept with it rather than stored as the session.
 *
 * A backend sign-in since the sign-out ends the debt: logging in moves the device to the
 * account that signed in, and the server deletes its rows under every other account then. Sent
 * after a sign-in of the same account, the unregister would delete that session's device instead.
 */
@Singleton
class DepartingDeviceUnregister internal constructor(
    private val owed: OwedUnregister,
    /** The HTTP status; throws [IOException] when no reply came back. */
    private val deleteDevice: suspend (base: String, deviceId: String, accessToken: String) -> Int,
    private val refresh: suspend (DepartingSession, sendsWithoutReply: Int) -> DepartingRefresh,
    private val hasSession: () -> Boolean,
    /** The backend the app talks to now, which the API endpoint setting decides. */
    private val currentBaseUrl: () -> String,
    private val now: () -> Long,
) {
    @Inject
    constructor(
        owed: OwedUnregister,
        api: PushApiClient,
        authTokenManager: AuthTokenManager,
        prefs: AppPreferences,
    ) : this(
        owed = owed,
        deleteDevice = api::deleteDevice,
        refresh = authTokenManager::refreshDeparting,
        hasSession = { authTokenManager.isLoggedIn },
        currentBaseUrl = { resolveAnnouncementEndpoint(prefs).url.trimEnd('/') },
        now = System::currentTimeMillis,
    )

    enum class Outcome {
        /** Nothing is owed any more: sent, or past sending. */
        DONE,

        /** Still owed: try again later, once there is a network. */
        RETRY,
    }

    private val store = owed.store

    // Shared with a backend sign-in; see OwedUnregister.signingIn.
    private val mutex = owed.mutex

    /** Keeps the unregister of [deviceId] as [session] until the server has it, in place of any owed before. */
    suspend fun owe(deviceId: String, session: DepartingSession) =
        mutex.withLock { store.save(PendingUnregister(deviceId, session, now(), session.sendsWithoutReply)) }

    /** Gives up on the debt, dropping the tokens it is kept with. */
    suspend fun abandon() = mutex.withLock { drop() }

    suspend fun send(): Outcome = mutex.withLock {
        val pending = try {
            store.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "owed unregister unreadable: ${e.javaClass.simpleName}")
            return@withLock Outcome.RETRY
        } ?: return@withLock Outcome.DONE
        if (hasSession()) {
            // See the class comment: a sign-in since moved the device to its account.
            drop()
            return@withLock Outcome.DONE
        }
        if (movedElsewhere(pending)) return@withLock Outcome.DONE
        if (now() - pending.owedAt >= OWED_AT_MOST_MS) {
            // Past the refresh token's lifetime nothing can go out as the account any more.
            Log.w(TAG, "the unregister was owed past the session's lifetime; it is dropped")
            drop()
            return@withLock Outcome.DONE
        }
        val outcome = try {
            sendAs(pending)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "unregister got no reply: ${e.logSummary()}")
            Outcome.RETRY
        } catch (e: Exception) {
            // A reply that could not be read, say. Named by class alone: a message about a
            // refresh reply could quote the tokens in it.
            Log.w(TAG, "unregister failed: ${e.javaClass.simpleName}")
            Outcome.RETRY
        }
        if (outcome == Outcome.DONE) drop()
        outcome
    }

    // Under the mutex.
    private fun drop() {
        runCatching { store.clear() }
            .onFailure { Log.w(TAG, "owed unregister not cleared: ${it.javaClass.simpleName}") }
    }

    /**
     * The API endpoint setting was changed since the sign-out: the tokens are dropped unsent,
     * rather than handed to the backend it names now, which did not issue them. For the setting's
     * own save, which re-registers the device at once.
     */
    fun dropIfEndpointMoved() {
        // Not waiting for an unregister on its way, which can take a while: it goes to the
        // backend it is owed to whatever the setting, and its next try checks the endpoint again.
        if (!mutex.tryLock()) return
        try {
            val pending = try {
                store.load()
            } catch (e: Exception) {
                Log.w(TAG, "owed unregister unreadable: ${e.javaClass.simpleName}")
                null
            }
            pending?.let(::movedElsewhere)
        } finally {
            mutex.unlock()
        }
    }

    // Under the mutex. Clears [pending] when the endpoint is not the one it is owed to.
    private fun movedElsewhere(pending: PendingUnregister): Boolean {
        if (pending.session.baseUrl == currentBaseUrl()) return false
        Log.w(TAG, "the API endpoint changed since the sign-out; the unregister is dropped")
        drop()
        return true
    }

    // Checked again right before each DELETE as well as before anything is sent: a session that
    // appeared meanwhile is a sign-in's, which moved the device to its account (class comment).
    // Its login waits for this lock, so this is a second line rather than the first.
    private suspend fun sendAs(pending: PendingUnregister): Outcome {
        var current = pending
        var refreshed = false
        if (current.session.accessExpiresAt - now() < MIN_TOKEN_LIFE_MS) {
            current = renew(current) ?: return afterFailedRefresh()
            refreshed = true
        }
        if (hasSession()) return Outcome.DONE
        // A stop that came during the refresh ends here, with the rotated pair kept.
        currentCoroutineContext().ensureActive()
        var status = deleteDevice(current.session.baseUrl, current.deviceId, current.session.accessToken)
        if (status == 401 && !refreshed) {
            // Turned down before its time, a revoked token, say: a refreshed one may still go out.
            current = renew(current) ?: return afterFailedRefresh()
            if (hasSession()) return Outcome.DONE
            status = deleteDevice(current.session.baseUrl, current.deviceId, current.session.accessToken)
        }
        return when {
            // 404: the device is not registered to the account any more.
            status in 200..299 || status == 404 -> Outcome.DONE
            status == 429 || status >= 500 -> Outcome.RETRY
            else -> {
                // Turned down for good: sent again it would only be turned down again.
                Log.w(TAG, "unregister refused: HTTP $status")
                Outcome.DONE
            }
        }
    }

    // Under the mutex, written by renew() for afterFailedRefresh() to read.
    private var refreshUnavailable = false

    /**
     * [pending] with a refreshed session, kept before it is used; null when the refresh failed.
     * Finishes even when the caller is cancelled, as a WorkManager stop does, as the session's own
     * refresh does: stopped between the send and the save, the pair the server rotated to would
     * be lost, and the token kept is one the server would take for reuse.
     */
    private suspend fun renew(pending: PendingUnregister): PendingUnregister? = withContext(NonCancellable) {
        var current = pending
        var reply: DepartingRefresh
        do {
            // Counted on disk before it goes out: a process ended during the send has it counted.
            // A reply that cannot be read throws, and stays counted, as one that never came.
            val sending = current.with(unansweredRefreshes = current.unansweredRefreshes + 1)
            if (!store.save(sending)) {
                // Counted in memory only, a process ended during the send would come back with
                // the count before it, and a later launch could make the token's third send.
                Log.w(TAG, "the refresh's send was not counted on disk; it is left for a later try")
                refreshUnavailable = true
                return@withContext null
            }
            reply = refresh(current.session, current.unansweredRefreshes)
            // Sent once more at most: the next call refuses at two.
            if (reply == DepartingRefresh.NoReply) current = sending
        } while (reply == DepartingRefresh.NoReply)
        refreshUnavailable = reply == DepartingRefresh.Unavailable || reply == DepartingRefresh.Unused
        when (reply) {
            // Kept before the DELETE: the server rotated the refresh token, and would take the one
            // it replaced for reuse if a later retry sent it.
            is DepartingRefresh.Refreshed ->
                current.with(session = reply.session, unansweredRefreshes = 0).also(store::save)
            // A 5xx: the send stays counted, as the backend may have rotated before a gateway
            // answered. The count on disk is already the send's.
            DepartingRefresh.Unavailable -> null
            DepartingRefresh.Unused -> {
                // Turned away, or never sent: the backend did not use the token, so the count
                // stands as it was. A put-back the disk refuses only leaves the send counted.
                store.save(current)
                null
            }
            else -> null
        }
    }

    private fun afterFailedRefresh(): Outcome =
        if (refreshUnavailable) {
            Outcome.RETRY
        } else {
            Log.w(TAG, "the departing session was turned down; the unregister cannot go out")
            Outcome.DONE
        }

    private companion object {
        const val TAG = "DepartingUnregister"

        // The access token must outlast the DELETE: a minute is ample for one request.
        const val MIN_TOKEN_LIFE_MS = 60_000L

        // The backend's refresh token lifetime (auth_refresh_token_ttl_days in its config).
        const val OWED_AT_MOST_MS = 90L * 24 * 60 * 60 * 1000
    }
}
