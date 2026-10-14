package org.ntust.app.tigerduck.push

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.util.logSummary
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device unregister a sign-out owes, and the lock everything that sends it or ends it takes:
 * [DepartingDeviceUnregister] while it refreshes and sends, and a backend sign-in
 * ([org.ntust.app.tigerduck.auth.AuthTokenManager.login]) while its login is on the way.
 */
@Singleton
class OwedUnregister internal constructor(internal val store: PendingUnregisterStore) {
    @Inject
    constructor(credentials: CredentialManager) : this(credentials.pendingUnregister)

    internal val mutex = Mutex()

    /**
     * Runs a backend sign-in's [login] under the lock, and ends the debt once it signed in.
     * Logging in moves the device to the account that signed in: the server deletes the device's
     * rows under every other account, and takes up its row again for the same one, which an
     * unregister sent after would delete, revoking the new session. Under the lock, no unregister
     * is on its way while the login is. A login that fails leaves the debt: the server moved
     * nothing, as far as this phone can tell.
     */
    suspend fun <T> signingIn(login: suspend () -> T): T = mutex.withLock {
        val result = login()
        runCatching { store.clear() }
            .onFailure { Log.w(TAG, "owed unregister not cleared after a sign-in: ${it.logSummary()}") }
        result
    }

    private companion object {
        const val TAG = "OwedUnregister"
    }
}
