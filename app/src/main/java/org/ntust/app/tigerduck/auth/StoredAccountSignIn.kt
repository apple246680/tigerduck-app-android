package org.ntust.app.tigerduck.auth

import android.util.Log
import org.ntust.app.tigerduck.auth.AuthTokenManager.SignInAttempt
import org.ntust.app.tigerduck.util.logSummary
import kotlin.coroutines.cancellation.CancellationException

/**
 * The automatic backend sign-in with the stored account, which [AuthTokenManager] runs as
 * [AuthTokenManager.signInWithStoredAccount]. Apart from [AuthService] so a test can run it.
 *
 * [harvestMoodleToken] gets a fresh token with the stored account and stores it only while that
 * account is still signed in. [afterSignIn] is not a suspending call: the sign-in can run inside a
 * request that PushRegistrationService makes while it holds the lock a registration takes.
 */
internal class StoredAccountSignIn(
    private val tokens: AuthTokenManager,
    private val isDemo: () -> Boolean,
    private val studentId: () -> String?,
    private val password: () -> String?,
    private val storedMoodleToken: () -> String?,
    private val harvestMoodleToken: suspend () -> String,
    private val afterSignIn: () -> Unit,
) {
    /**
     * Signs in with [signIn], the generation read before the credentials, so a sign-out during
     * the harvest keeps the login from storing the departing account's pair.
     */
    suspend fun signIn(signIn: Int): SignInAttempt {
        if (isDemo()) return SignInAttempt.NOTHING_TO_SEND
        val studentId = studentId() ?: return SignInAttempt.NOTHING_TO_SEND
        val password = password()
        // Harvest a FRESH Moodle token before the v3 login — the backend
        // verifies it against moodle2, and the stored one may be stale, which
        // gets the login rejected with 401 invalid_token (see AuthService.login).
        // Best-effort: fall back to the stored token if the harvest fails.
        val moodleToken = if (password != null) {
            try {
                harvestMoodleToken()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "auto sign-in: Moodle token harvest failed; using stored: ${e.logSummary()}")
                storedMoodleToken()
            }
        } else {
            storedMoodleToken()
        }
        if (moodleToken.isNullOrEmpty()) {
            // With a password a harvest was tried.
            return if (password != null) SignInAttempt.FAILED else SignInAttempt.NOTHING_TO_SEND
        }
        // Signed out during the harvest: the stored token it fell back to may
        // be the next account's, and must not go out with this one's ID.
        if (tokens.signInGeneration != signIn) return SignInAttempt.FAILED
        return try {
            tokens.login(
                studentId = studentId,
                password = password ?: "",
                moodleToken = moodleToken,
                moodlePrivateToken = null,
                signInGeneration = signIn,
            )
            Log.i(TAG, "auto sign-in: v3 JWT obtained")
            afterSignIn()
            SignInAttempt.SIGNED_IN
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "auto sign-in failed: ${e.logSummary()}")
            SignInAttempt.FAILED
        }
    }

    private companion object {
        const val TAG = "AuthService"
    }
}
