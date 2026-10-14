package org.ntust.app.tigerduck.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.demo.DemoAccount
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether a library account is signed in, as the library row in Settings shows
 * it: a library token that is still valid, or the demo library. Flip-to-library
 * listens only while one is, and its switch reads off until then.
 *
 * The stores behind it say nothing when they change, and a token also runs out
 * with time, so whatever changes them calls [refresh]: a library sign-in or
 * sign-out, the school's, which can take the library with it, and each return
 * to the app. Signed out, the QR code kept in [LibraryQRCache] goes too: it
 * still scans as the account's pass.
 */
@Singleton
class LibrarySession internal constructor(
    private val read: () -> Boolean,
    private val onSignedOut: () -> Unit = {},
) {

    @Inject
    constructor(credentials: CredentialManager, demoAccount: DemoAccount, qrCache: LibraryQRBitmapCache) :
        this({ credentials.isLibraryTokenValid || demoAccount.isLibrarySignedIn }, qrCache::clear)

    private val _signedIn = MutableStateFlow(read())
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    /** Reads the stores again; returns whether a library account is signed in. */
    fun refresh(): Boolean = read().also {
        _signedIn.value = it
        if (!it) onSignedOut()
    }
}
