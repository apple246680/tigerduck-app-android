package org.ntust.app.tigerduck.auth

import android.util.Log
import org.ntust.app.tigerduck.shared.LibraryCredentialStore
import org.ntust.app.tigerduck.shared.LibraryService
import org.ntust.app.tigerduck.util.logSummary
import kotlin.coroutines.cancellation.CancellationException

/**
 * The SSO sign-in to the school, and the library sign-in it makes with the same
 * credentials while no library token is valid. Apart from [AuthService] so a
 * test can run it; [AuthService] holds its lock around each call.
 *
 * Each reads the library account generation before anything else, the stored
 * account included: a sign-out from then on takes the library account too, and
 * the library sign-in then stores nothing for the account that left. That
 * sign-in is one the app makes on its own, so it also yields to a library
 * account the user signed in to meanwhile.
 */
internal class SchoolSignIn(
    /** What [libraryService] stores into. */
    private val libraryStore: LibraryCredentialStore,
    private val libraryService: LibraryService,
    private val studentId: () -> String?,
    private val password: () -> String?,
    private val sessionIsWarm: () -> Boolean,
    /** The SSO step; [typed] for credentials just entered — see [org.ntust.app.tigerduck.network.SsoLoginService.signIn]. */
    private val signInToSchool: suspend (studentId: String, password: String, typed: Boolean) -> Boolean,
    private val afterLibrarySignIn: () -> Unit,
) {
    /** A sign-in with credentials just entered. Throws what the SSO step throws; the library step only logs. */
    suspend fun typed(studentId: String, password: String): Boolean =
        signIn(studentId, password, typed = true, libraryAccount = libraryStore.libraryAccountGeneration)

    /**
     * The stored account again: false without one, and true at once while the
     * session is still warm. Throws what reading the stored account or the SSO
     * step throws; the library step only logs.
     */
    suspend fun stored(): Boolean {
        val libraryAccount = libraryStore.libraryAccountGeneration
        val studentId = studentId() ?: return false
        val password = password() ?: return false
        if (sessionIsWarm()) return true
        return signIn(studentId.trim().uppercase(), password, typed = false, libraryAccount = libraryAccount)
    }

    private suspend fun signIn(studentId: String, password: String, typed: Boolean, libraryAccount: Int): Boolean {
        val success = signInToSchool(studentId, password, typed)
        if (success && !libraryStore.isLibraryTokenValid) {
            // Best-effort: library credentials may differ from NTUST SSO, so a
            // failure here must not fail the SSO login — but log it, otherwise
            // "library QR never works" is undiagnosable in the field. That
            // includes an answer dropped for an account that signed out.
            try {
                libraryService.login(studentId, password, libraryAccount, byUser = false)
                afterLibrarySignIn()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Library login failed (best-effort): ${e.logSummary()}")
            }
        }
        return success
    }

    private companion object {
        const val TAG = "AuthService"
    }
}
