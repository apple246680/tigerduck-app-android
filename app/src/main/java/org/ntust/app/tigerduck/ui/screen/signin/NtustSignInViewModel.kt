package org.ntust.app.tigerduck.ui.screen.signin

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.notification.BackgroundSyncWorker
import org.ntust.app.tigerduck.ui.AppState
import javax.inject.Inject

/**
 * Whether the TigerSync status asks the user to sign in again, in place of the latest error: sync
 * is on, the school account is signed in with credentials to read, the device has no TigerSync
 * session, and the last sign-in that had something to send failed, so none came. Not while
 * another is under way, which may yet succeed ([AuthTokenManager.lastSignInFailed] reads false
 * then). Mirrors iOS's `AuthService.asksToSignInAgain`.
 */
internal fun asksToSignInAgain(
    syncEnabled: Boolean,
    credentialsReadable: Boolean,
    hasBackendSession: Boolean,
    lastSignInFailed: Boolean,
): Boolean = syncEnabled && credentialsReadable && !hasBackendSession && lastSignInFailed

/**
 * "Sign in again" after the automatic sign-in failed: the TigerSync status row, and the school
 * sign-in sheet it opens over any page ([NtustSignInSheetHost]). The sheet signs in as the stored account; the backend sign-in that
 * follows it signs in to TigerSync too.
 */
@HiltViewModel
class NtustSignInViewModel @Inject constructor(
    private val appState: AppState,
    private val authService: AuthService,
    private val authTokenManager: AuthTokenManager,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    val lastSignInFailed: StateFlow<Boolean> = authTokenManager.lastSignInFailed
    val isLoggingIn: StateFlow<Boolean> = authService.isLoggingIn
    val loginError: StateFlow<String?> = authService.loginError

    val storedStudentId: String? get() = authService.storedStudentId

    /** Snapshot state, so a composable reading it follows the request. */
    val sheetRequested: Boolean get() = appState.ntustSignInSheetRequested

    /** [asksToSignInAgain] for this device now, after [lastSignInFailed]. */
    fun asksToSignInAgain(syncEnabled: Boolean, lastSignInFailed: Boolean): Boolean = asksToSignInAgain(
        syncEnabled = syncEnabled,
        credentialsReadable = authService.storedStudentId != null && authService.storedPassword != null,
        hasBackendSession = authTokenManager.isLoggedIn,
        lastSignInFailed = lastSignInFailed,
    )

    fun requestSignIn() {
        // An error left from an earlier sign-in is not this one's.
        authService.clearLoginError()
        appState.ntustSignInSheetRequested = true
    }

    fun dismissSheet() {
        appState.ntustSignInSheetRequested = false
        authService.clearLoginError()
    }

    fun signIn(studentId: String, password: String) {
        viewModelScope.launch {
            if (authService.login(studentId, password)) {
                // As Settings does after a sign-in, in case it had stopped.
                BackgroundSyncWorker.schedule(context)
                appState.ntustSignInSheetRequested = false
            }
        }
    }
}
