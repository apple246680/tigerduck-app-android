package org.ntust.app.tigerduck.push

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.auth.DepartingRefresh
import org.ntust.app.tigerduck.auth.DepartingSession
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.util.logSummary
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** The device unregister a sign-out still owes the backend, with the session it goes out as. */
class PendingUnregister(val deviceId: String, val session: DepartingSession)

/** Keeps the owed unregister between launches. It holds tokens, so it is stored encrypted. */
interface PendingUnregisterStore {
    fun load(): PendingUnregister?
    fun save(pending: PendingUnregister)
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
    private val store: PendingUnregisterStore,
    /** The HTTP status; throws [IOException] when no reply came back. */
    private val deleteDevice: suspend (base: String, deviceId: String, accessToken: String) -> Int,
    private val refresh: suspend (DepartingSession) -> DepartingRefresh,
    private val hasSession: () -> Boolean,
    /** The backend the app talks to now, which the API endpoint setting decides. */
    private val currentBaseUrl: () -> String,
    private val now: () -> Long,
) {
    @Inject
    constructor(
        credentials: CredentialManager,
        api: PushApiClient,
        authTokenManager: AuthTokenManager,
        prefs: AppPreferences,
    ) : this(
        store = credentials.pendingUnregister,
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

    private val mutex = Mutex()

    /** Keeps [pending] until the server has it, in place of any owed before. */
    suspend fun owe(pending: PendingUnregister) = mutex.withLock { store.save(pending) }

    suspend fun send(): Outcome = mutex.withLock {
        val pending = store.load() ?: return@withLock Outcome.DONE
        if (hasSession()) {
            // See the class comment: a sign-in since moved the device to its account.
            store.clear()
            return@withLock Outcome.DONE
        }
        if (movedElsewhere(pending)) return@withLock Outcome.DONE
        val outcome = try {
            sendAs(pending)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "unregister got no reply: ${e.logSummary()}")
            Outcome.RETRY
        }
        if (outcome == Outcome.DONE) store.clear()
        outcome
    }

    /**
     * The API endpoint setting was changed since the sign-out: the tokens are dropped unsent,
     * rather than handed to the backend it names now, which did not issue them. For the setting's
     * own save, which re-registers the device at once.
     */
    suspend fun dropIfEndpointMoved() = mutex.withLock {
        store.load()?.let(::movedElsewhere)
    }

    // Under the mutex. Clears [pending] when the endpoint is not the one it is owed to.
    private fun movedElsewhere(pending: PendingUnregister): Boolean {
        if (pending.session.baseUrl == currentBaseUrl()) return false
        Log.w(TAG, "the API endpoint changed since the sign-out; the unregister is dropped")
        store.clear()
        return true
    }

    private suspend fun sendAs(pending: PendingUnregister): Outcome {
        var current = pending
        var refreshed = false
        if (current.session.accessExpiresAt - now() < MIN_TOKEN_LIFE_MS) {
            current = renew(current) ?: return afterFailedRefresh()
            refreshed = true
        }
        var status = deleteDevice(current.session.baseUrl, current.deviceId, current.session.accessToken)
        if (status == 401 && !refreshed) {
            // Turned down before its time, a revoked token, say: a refreshed one may still go out.
            current = renew(current) ?: return afterFailedRefresh()
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

    /** [pending] with a refreshed session, kept before it is used; null when the refresh failed. */
    private suspend fun renew(pending: PendingUnregister): PendingUnregister? {
        val reply = refresh(pending.session)
        refreshUnavailable = reply == DepartingRefresh.Unavailable
        if (reply !is DepartingRefresh.Refreshed) return null
        // Kept before the DELETE: the server rotated the refresh token, and would take the one it
        // replaced for reuse if a later retry sent it.
        return PendingUnregister(pending.deviceId, reply.session).also(store::save)
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
    }
}
