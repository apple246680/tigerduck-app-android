package org.ntust.app.tigerduck.auth

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.liveactivity.LiveActivityManager
import org.ntust.app.tigerduck.notification.AccountNotifications
import org.ntust.app.tigerduck.notification.AssignmentNotificationScheduler
import org.ntust.app.tigerduck.notification.BackgroundSyncWorker
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What follows the school account's sign-in and sign-out, wherever they came from: the
 * onboarding wizard, Settings, the full reset. Each used to do its own share: the wizard did
 * none, so a student who signed in there got no background sync until the app was next created
 * from scratch, and the full reset left the reminders and the Live Update running.
 *
 * Follows [AuthService.authState] rather than the pages, and only its changes: the state the
 * process starts in is not one. MainActivity schedules the background sync for an account
 * already signed in at launch.
 */
@Singleton
class AccountSessionEffects internal constructor(
    private val context: Context,
    private val authState: StateFlow<Boolean>,
    private val assignmentReminders: AssignmentNotificationScheduler,
    private val refreshLiveUpdates: () -> Unit,
    private val stopLiveUpdates: () -> Unit,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        authService: AuthService,
        assignmentReminders: AssignmentNotificationScheduler,
        liveActivityManager: LiveActivityManager,
    ) : this(
        context = context,
        authState = authService.authState,
        assignmentReminders = assignmentReminders,
        refreshLiveUpdates = { liveActivityManager.refresh() },
        stopLiveUpdates = liveActivityManager::stop,
    )

    /** Follows the sign-in state on [scope] for the life of the process. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            var wasSignedIn: Boolean? = null
            authState.collect { signedIn ->
                val before = wasSignedIn
                wasSignedIn = signedIn
                if (before == null || before == signedIn) return@collect
                if (signedIn) onSignedIn() else onSignedOut()
            }
        }
    }

    private fun onSignedIn() {
        BackgroundSyncWorker.schedule(context)
        refreshLiveUpdates()
    }

    private fun onSignedOut() {
        BackgroundSyncWorker.cancel(context)
        stopLiveUpdates()
        assignmentReminders.cancelAllTracked()
        // What the departing student was shown stays in the shade otherwise.
        AccountNotifications.cancelAll(context)
    }
}
