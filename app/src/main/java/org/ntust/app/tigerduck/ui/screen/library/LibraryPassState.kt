package org.ntust.app.tigerduck.ui.screen.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.ntust.app.tigerduck.library.LibraryQRCache

/**
 * Whether the Library page shows the pass or the sign-in form, the pass's
 * code cycle, and the failure the page says. Apart from [LibraryViewModel] so
 * a test can run them together.
 */
internal class LibraryPassState<Image : Any>(
    scope: CoroutineScope,
    /** Whether the page has an account to show the pass for; see [LibraryPageAccount.hasAccount]. */
    private val hasAccount: () -> Boolean,
    /** The account a code belongs to; see [LibraryPageAccount.codeAccount]. */
    codeAccount: () -> Int,
    fetch: suspend () -> String,
    render: suspend (String) -> Image,
    /** The library sign-in state Settings and the flip follow. */
    signedIn: Flow<Boolean>,
    /** Reads that state again. */
    private val refreshSession: () -> Unit,
    describe: (Exception) -> String,
    cache: LibraryQRCache<Image>,
    now: () -> Long,
) {
    /** What failed, for the page to say; a sign-in on the page writes it too. */
    val errors = MutableStateFlow<String?>(null)

    private val _isLoggedIn = MutableStateFlow(hasAccount())

    /** Whether the page shows the pass rather than the sign-in form. */
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val cycle = LibraryQRCycle(
        scope = scope,
        fetch = fetch,
        render = render,
        account = codeAccount,
        sessionValid = hasAccount,
        // The account is gone (refused, or signed out): back to the sign-in
        // form instead of looping on it. Before the session is read again,
        // so the drop that follows it keeps this failure's message.
        onSessionEnded = {
            _isLoggedIn.value = false
            refreshSession()
        },
        describe = describe,
        errors = errors,
        cache = cache,
        now = now,
    )

    val image: StateFlow<Image?> = cycle.image
    val countdown: StateFlow<Int> = cycle.countdown
    val isLoading: StateFlow<Boolean> = cycle.isLoading

    init {
        // Signed out elsewhere, such as a refusal the sign-in at a return to
        // the app found: back to the sign-in form at once, also while a code
        // request backs off, rather than at its next try. That failure's
        // message is no longer what happened. A code request still out is
        // waited for first, for a while: one whose sign-in the library
        // refused ends the session itself and says so, and stopping it would
        // lose that; one that hangs is stopped.
        scope.followSignOuts(signedIn, hasAccount) {
            withTimeoutOrNull(SIGN_OUT_WAIT_MS) { cycle.fetching.first { !it } }
            if (_isLoggedIn.value && !hasAccount()) {
                cycle.clear()
                errors.value = null
                _isLoggedIn.value = false
            }
        }
    }

    /** The page opens: the pass and its code while there is an account. */
    fun load() {
        _isLoggedIn.value = hasAccount()
        if (_isLoggedIn.value) cycle.start()
    }

    /**
     * The page comes back: the pause stopped the cycle, and a sign-in or a
     * sign-out may have come from another screen meanwhile.
     */
    fun resume() {
        _isLoggedIn.value = hasAccount()
        if (_isLoggedIn.value) {
            cycle.start()
        } else {
            // Signed out elsewhere: the code on screen was that account's.
            cycle.clear()
        }
    }

    fun pause() = cycle.stop()

    /** A sign-in on the page landed: the pass, with a new code. */
    fun signedIn() {
        _isLoggedIn.value = true
        cycle.restart()
    }

    companion object {
        /**
         * How long a sign-out elsewhere waits for a code request still out.
         * The library client has no overall timeout, so a hanging request
         * could hold the pass up for its connect and read timeouts.
         */
        const val SIGN_OUT_WAIT_MS = 15_000L
    }
}
