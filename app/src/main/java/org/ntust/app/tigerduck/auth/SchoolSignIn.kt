package org.ntust.app.tigerduck.auth

import android.util.Log
import org.ntust.app.tigerduck.shared.LibraryCredentialStore
import org.ntust.app.tigerduck.shared.LibraryService
import org.ntust.app.tigerduck.util.logSummary
import kotlin.coroutines.cancellation.CancellationException

/**
 * The SSO sign-in to the school, and the library sign-in a typed one makes with
 * the same credentials while no library token is valid and no library account
 * of another student can sign in by itself. Apart from
 * [AuthService] so a test can run it; [AuthService] holds its lock around each
 * call.
 *
 * The typed sign-in reads the library account generation and the school
 * account's before anything else: a school sign-out from then on, which takes
 * the library account only when it is the same student's, keeps the library
 * sign-in from storing anything for the account that left. That sign-in is
 * one the app makes on its own, so it also yields to a library account the
 * user signed in to meanwhile.
 *
 * Signing in again with the stored school account leaves the library alone:
 * only the stored library credentials renew its token, under the rules in
 * [LibraryService.ensureToken], and a library account the user signed out of
 * stays signed out.
 */
internal class SchoolSignIn(
    /** What [libraryService] stores into. */
    private val libraryStore: LibraryCredentialStore,
    private val libraryService: LibraryService,
    /** Moved by each school sign-out; see [org.ntust.app.tigerduck.data.preferences.CredentialManager.schoolAccountGeneration]. */
    private val schoolAccount: () -> Int,
    /** Whether no school sign-out has come since the [schoolAccount] given was read. */
    private val isSchoolAccount: (Int) -> Boolean,
    private val studentId: () -> String?,
    private val password: () -> String?,
    private val sessionIsWarm: () -> Boolean,
    /** The SSO step; [typed] for credentials just entered — see [org.ntust.app.tigerduck.network.SsoLoginService.signIn]. */
    private val signInToSchool: suspend (studentId: String, password: String, typed: Boolean) -> Boolean,
    private val afterLibrarySignIn: () -> Unit,
) {
    /** A sign-in with credentials just entered. Throws what the SSO step throws; the library step only logs. */
    suspend fun typed(studentId: String, password: String): Boolean {
        // The school account first: a school sign-out between the two reads
        // that keeps another student's library account moves only it.
        val school = schoolAccount()
        val libraryAccount = libraryStore.libraryAccountGeneration
        val success = signInToSchool(studentId, password, true)
        // A library account of another student ID that can still sign in by
        // itself stays, as a school sign-out keeps it (as on the iPhone,
        // tigerduck-app/tigerduck-app#542). With none, a refused one, or this
        // student's own with its token run out, the school credentials sign in.
        if (success && !libraryStore.isLibraryTokenValid && !libraryService.hasAccountOtherThan(studentId)) {
            signInToLibrary(studentId, password, libraryAccount, school)
        }
        return success
    }

    /**
     * The stored account again: false without one, and true at once while the
     * session is still warm. Throws what reading the stored account or the SSO
     * step throws. Signs nothing in to the library.
     */
    suspend fun stored(): Boolean {
        val studentId = studentId() ?: return false
        val password = password() ?: return false
        if (sessionIsWarm()) return true
        return signInToSchool(studentId.trim().uppercase(), password, false)
    }

    private suspend fun signInToLibrary(studentId: String, password: String, libraryAccount: Int, school: Int) {
        // Best-effort: library credentials may differ from NTUST SSO, so a
        // failure here must not fail the SSO login — but log it, otherwise
        // "library QR never works" is undiagnosable in the field. That
        // includes an answer dropped for an account that signed out.
        try {
            libraryService.login(studentId, password, libraryAccount, byUser = false) { isSchoolAccount(school) }
            afterLibrarySignIn()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Library login failed (best-effort): ${e.logSummary()}")
        }
    }

    private companion object {
        const val TAG = "AuthService"
    }
}
