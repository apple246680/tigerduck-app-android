package org.ntust.app.tigerduck.review

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.preferences.ReviewPromptStore
import org.ntust.app.tigerduck.demo.DemoAccount
import org.ntust.app.tigerduck.shared.clock.AppClock
import org.ntust.app.tigerduck.ui.AppState
import org.ntust.app.tigerduck.ui.component.ServerStatus
import org.ntust.app.tigerduck.ui.component.ServerStatusTracker
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * When to ask for a Play Store rating (tigerduck-app-android#332): once,
 * ever, after the class table has loaded on [DAYS_OF_USE] different days,
 * and at a calm moment — the user coming back to Home or the class table
 * with a successful load behind them. Not on a cold start before anything
 * shows, not while signed out, signing in, in the wizard or in the demo
 * account, and not right after a load failed.
 *
 * The Home and class table view models report their fetches
 * ([classTableLoaded], [loadFailed]) and the returns to their pages
 * ([calmMoment]); `MainActivity` watches [pending], launches Play's flow
 * through `InAppReview` and reports back with [launched]. The days are
 * counted in [ReviewPromptStore], so they survive restarts and upgrades;
 * what happened in this process — a load, a failure, an attempt — is kept
 * here, so a new process has to see the class table load again before it
 * asks.
 *
 * Flavor-neutral: on fdroid `InAppReview` is a stub that never launches, so
 * [launched] is never told true there and nothing is recorded as asked.
 */
@Singleton
class ReviewPrompt(
    private val store: ReviewPromptStore,
    private val today: () -> LocalDate,
    private val mayAskNow: () -> Boolean,
) {
    @Inject
    constructor(
        store: ReviewPromptStore,
        authService: AuthService,
        appState: AppState,
        demoAccount: DemoAccount,
    ) : this(
        store = store,
        // The app's clock, so a debug date moves the days with everything else.
        today = { AppClock.localDateTime().toLocalDate() },
        mayAskNow = {
            authService.authState.value &&
                !authService.isLoggingIn.value &&
                !appState.ntustSignInSheetRequested &&
                !appState.showOnboarding &&
                !demoAccount.isActive &&
                // A red status dot is an error still on screen.
                ServerStatusTracker.statuses.value.values.none { it == ServerStatus.FAILED }
        },
    )

    private val _pending = MutableStateFlow(false)

    /** Whether the flow is to be launched now; answered with [launched]. */
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    // This process only: whether the class table has loaded with no failure
    // since, and whether the flow was already attempted. Both go with the
    // process on purpose — see the class comment.
    @Volatile
    private var loaded = false

    @Volatile
    private var attempted = false

    /**
     * The class table's courses were fetched and saved. Counts today once,
     * however many times that happens today.
     */
    @Synchronized
    fun classTableLoaded() {
        loaded = true
        val day = today().toString()
        if (store.reviewPromptLastDay == day) return
        store.reviewPromptDaysUsed = store.reviewPromptDaysUsed + 1
        store.reviewPromptLastDay = day
    }

    /**
     * A fetch failed, or could not start. Not a moment to ask, and an ask
     * not launched yet is withdrawn, until a load goes through again.
     */
    @Synchronized
    fun loadFailed() {
        loaded = false
        _pending.value = false
    }

    /** The user came back to Home or the class table: ask now if everything says so. */
    @Synchronized
    fun calmMoment() {
        if (attempted || !loaded || store.reviewPromptAsked) return
        if (store.reviewPromptDaysUsed < DAYS_OF_USE) return
        if (!mayAskNow()) return
        _pending.value = true
    }

    /**
     * `InAppReview.launch` returned. True: Play's flow was started, which is
     * the one "asked" the API gives — whether the dialog showed is not said —
     * so it is recorded for good. False: Play would not hand out the flow
     * (no Play Store, a sideloaded install, offline), so nothing is recorded,
     * and it is not tried again before the next process.
     */
    @Synchronized
    fun launched(launched: Boolean) {
        attempted = true
        _pending.value = false
        if (launched) store.reviewPromptAsked = true
    }

    companion object {
        /** Calendar days on which the class table must have loaded before the one ask. */
        const val DAYS_OF_USE = 5
    }
}
