package org.ntust.app.tigerduck.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.auth.PasswordPosting
import org.ntust.app.tigerduck.auth.StoredPasswordLatch
import org.ntust.app.tigerduck.auth.guardAutomaticHarvest

/**
 * When [MoodleTokenService] signs in to SSO for a token, and where it keeps it. Apart from the
 * service's SSO flow so a test can run it: [login] is that flow, a student ID and password in, a
 * wstoken out. It posts the password, and reads SSO's answer to it, through the
 * [PasswordPosting] it is handed.
 *
 * A refresh and a renewal sign in with the stored password the user did not just type, so they
 * run under [passwordLatch]: none at all once SSO has refused that password, a check again right
 * before the password goes out under the lock every automatic post takes, and a refusal latched
 * for the whole app. The sign-in the user makes ([obtain]) posts what they typed.
 *
 * One sign-in at a time: the sign-in a user makes, a refresh and a renewal all take [mutex]. A
 * refresh and a renewal both go through [MoodleTokenRenewal], so both keep its pause and its stop
 * at a refused password, and check the stored token again once they hold the lock: one that
 * waited behind a sign-in takes the token that sign-in got rather than signing in a second time.
 */
internal class MoodleTokenCoordinator(
    private val tokens: Tokens,
    private val login: suspend (
        studentId: String,
        password: String,
        posting: PasswordPosting,
    ) -> String,
    scope: CoroutineScope,
    now: () -> Long,
    pauseMs: Long,
    private val passwordLatch: StoredPasswordLatch,
) {
    /** Where the token and the account it is harvested with are stored. */
    interface Tokens {
        var token: String?
        val studentId: String?
        val password: String?

        /**
         * Stores [token] only while [studentId] is still the stored account, as one step with a
         * sign-out's clear, so a sign-out can never land between the check and the store.
         */
        fun storeIfSignedIn(studentId: String, token: String): Boolean
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
        refusal = LatchedRefusal(passwordLatch),
    )

    /**
     * A fresh token with the stored account: the stored one when a sign-in got it moments ago,
     * else a new one; see [MoodleTokenRenewal.refresh].
     */
    suspend fun refresh(): String = renewal.refresh()

    /** A token in place of [rejected]; see [MoodleTokenRenewal]. */
    suspend fun renew(rejected: String?): String = renewal.renew(rejected)

    /**
     * A token with credentials the user just entered, which clears what held renewals back. It is
     * stored only while [studentId] is still the stored account, as one step with a sign-out's
     * clear: a sign-out during the sign-in leaves none of the departing student's behind.
     */
    suspend fun obtain(studentId: String, password: String): String = mutex.withLock {
        val normalized = studentId.trim().uppercase()
        login(normalized, password, PasswordPosting.Typed).also {
            // Known as just got before it is stored, so a refresh that reads it takes it.
            renewal.reset(harvested = it, credentials = studentId to password)
            if (!tokens.storeIfSignedIn(normalized, it)) {
                renewal.reset()
                throw MoodleWebserviceError.MissingStoredCredentials()
            }
        }
    }

    private suspend fun refreshUnlocked(): String {
        val sid = tokens.studentId ?: throw MoodleWebserviceError.MissingStoredCredentials()
        val pwd = tokens.password ?: throw MoodleWebserviceError.MissingStoredCredentials()
        val token = passwordLatch.guardAutomaticHarvest(sid, pwd) { posting ->
            login(sid.trim().uppercase(), pwd, posting)
        }
        // Signed out while it ran: the token is the departing account's, and stored it would
        // answer for whoever signs in next.
        if (!tokens.storeIfSignedIn(sid, token)) throw MoodleWebserviceError.MissingStoredCredentials()
        return token
    }
}

/**
 * [StoredPasswordRefusal] as the app-wide [StoredPasswordLatch], a flag, so no fingerprint of the
 * credentials is ever kept. Refused while the latch holds. [guardAutomaticHarvest] marks it, with
 * the school's message and only for the password still stored; a sign-in by hand lifts it.
 */
internal class LatchedRefusal(private val latch: StoredPasswordLatch) : StoredPasswordRefusal {
    override fun isRefused(fingerprint: String): Boolean = latch.isRejected
    override fun markRefused(fingerprint: String) = Unit
    override fun clear() = latch.clear()
}
