package org.ntust.app.tigerduck.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * When [MoodleTokenService] signs in to SSO for a token, and where it keeps it. Apart from the
 * service's SSO flow so a test can run it: [login] is that flow, a student ID and password in, a
 * wstoken out.
 *
 * One sign-in at a time: the sign-in a user makes, a refresh and a renewal all take [mutex]. A
 * refresh and a renewal both go through [MoodleTokenRenewal], so both keep its pause and its stop
 * at a refused password, and check the stored token again once they hold the lock: one that
 * waited behind a sign-in takes the token that sign-in got rather than signing in a second time.
 */
internal class MoodleTokenCoordinator(
    private val tokens: Tokens,
    private val login: suspend (studentId: String, password: String) -> String,
    scope: CoroutineScope,
    now: () -> Long,
    pauseMs: Long,
    refusal: StoredPasswordRefusal = StoredPasswordRefusal.InMemory(),
) {
    /** Where the token and the account it is harvested with are stored. */
    interface Tokens {
        var token: String?
        val studentId: String?
        val password: String?
    }

    private val mutex = Mutex()

    private val renewal = MoodleTokenRenewal(
        storedToken = { tokens.token },
        credentials = {
            val id = tokens.studentId
            val password = tokens.password
            if (id == null || password == null) null else id to password
        },
        harvest = { rejected ->
            mutex.withLock {
                val stored = tokens.token
                if (!stored.isNullOrEmpty() && stored != rejected) stored else refreshUnlocked()
            }
        },
        now = now,
        scope = scope,
        pauseMs = pauseMs,
        refusal = refusal,
    )

    /**
     * A fresh token with the stored account: the stored one when a sign-in got it moments ago,
     * else a new one; see [MoodleTokenRenewal.refresh].
     */
    suspend fun refresh(): String = renewal.refresh()

    /** A token in place of [rejected]; see [MoodleTokenRenewal]. */
    suspend fun renew(rejected: String?): String = renewal.renew(rejected)

    /** A token with credentials the user just entered, which clears what held renewals back. */
    suspend fun obtain(studentId: String, password: String): String {
        val token = mutex.withLock {
            login(studentId.trim().uppercase(), password).also { tokens.token = it }
        }
        renewal.reset(harvested = token)
        return token
    }

    private suspend fun refreshUnlocked(): String {
        val sid = tokens.studentId ?: throw MoodleWebserviceError.MissingStoredCredentials()
        val pwd = tokens.password ?: throw MoodleWebserviceError.MissingStoredCredentials()
        val token = login(sid.trim().uppercase(), pwd)
        // Signed out while it ran: the token is the departing account's, and stored it would
        // answer for whoever signs in next.
        if (tokens.studentId != sid) throw MoodleWebserviceError.MissingStoredCredentials()
        tokens.token = token
        return token
    }
}
