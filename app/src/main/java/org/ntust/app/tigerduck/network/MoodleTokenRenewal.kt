package org.ntust.app.tigerduck.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

/**
 * When the Moodle token is renewed, which posts the stored password to NTUST's SSO. Every Moodle
 * call that finds the token dead asks, often several at once (a course detail refreshes four
 * kinds of data side by side), so this keeps it, as iOS's `refreshTokenIfNeeded` does, to:
 * - one sign-in at a time, whose answer, a failure included, every caller waiting shares;
 * - none for [pauseMs] after one that failed for a reason other than the network, whose error
 *   is handed back instead;
 * - none at all with credentials SSO refused, until they change. Retrying a wrong password is
 *   what locks an NTUST account.
 *
 * [harvest] signs in and stores the token it gets. It runs in [scope], so a caller that goes away
 * does not cancel a sign-in others wait on.
 */
internal class MoodleTokenRenewal(
    private val storedToken: () -> String?,
    /** The stored student ID and password, or null with none stored. */
    private val credentials: () -> Pair<String, String>?,
    /** Signs in for a token in place of the rejected one it is handed. */
    private val harvest: suspend (rejected: String?) -> String,
    private val now: () -> Long,
    private val scope: CoroutineScope,
    private val pauseMs: Long,
) {
    private val lock = Mutex()
    /**
     * The sign-in under way. It completes with its failure as a value rather than failing, so it
     * never cancels the scope it runs in, whatever kind of scope that is.
     */
    private var inFlight: Deferred<Result<String>>? = null

    /** The last failure that was not the network's, with when and for which credentials. */
    private var lastFailure: Failure? = null

    /** The credentials SSO refused, as a fingerprint; never the password itself. */
    private var refusedCredentials: String? = null

    private class Failure(val atMs: Long, val error: Throwable, val credentials: String)

    /** A token in place of [rejected], which is null when none was stored. */
    suspend fun renew(rejected: String?): String {
        val pending = lock.withLock {
            inFlight?.takeIf { it.isActive } ?: run {
                val stored = storedToken()
                if (!stored.isNullOrEmpty() && stored != rejected) return stored
                val key = credentials()?.let(::fingerprint)
                    ?: throw MoodleWebserviceError.MissingStoredCredentials()
                if (key == refusedCredentials) throw MoodleWebserviceError.SsoLoginRejected(null)
                lastFailure?.let { failure ->
                    // Measured from the failure, so a clock set back does not stretch the pause.
                    val age = now() - failure.atMs
                    if (failure.credentials == key && age in 0 until pauseMs) throw failure.error
                }
                scope.async {
                    try {
                        Result.success(signIn(key, rejected))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }.also { inFlight = it }
            }
        }
        return pending.await().getOrThrow()
    }

    /** Forgets what failed, for a sign-in the user made by hand. */
    suspend fun reset() = lock.withLock {
        lastFailure = null
        refusedCredentials = null
    }

    private suspend fun signIn(key: String, rejected: String?): String = try {
        harvest(rejected).also { lock.withLock { lastFailure = null } }
    } catch (e: CancellationException) {
        throw e
    } catch (e: MoodleWebserviceError.SsoLoginRejected) {
        // A sign-in with a new password can finish while this one runs on the old.
        lock.withLock { if (credentials()?.let(::fingerprint) == key) refusedCredentials = key }
        throw e
    } catch (e: Exception) {
        // Offline the sign-in mostly stops before the password goes out, and a pause would
        // outlast the network coming back.
        if (!MoodleWebserviceError.isNetwork(e)) {
            lock.withLock {
                if (credentials()?.let(::fingerprint) == key) lastFailure = Failure(now(), e, key)
            }
        }
        throw e
    }

    private fun fingerprint(credentials: Pair<String, String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${credentials.first.trim().uppercase()}\u0000${credentials.second}".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
