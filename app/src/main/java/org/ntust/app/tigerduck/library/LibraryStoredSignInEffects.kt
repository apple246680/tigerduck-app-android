package org.ntust.app.tigerduck.library

import org.ntust.app.tigerduck.shared.LibraryService

/**
 * What the app does after a sign-in with the stored library credentials, which
 * no screen asked for: read the sign-in state again for Settings, the page and
 * the flip, and after a refusal take the password off the watch.
 */
internal suspend fun onStoredLibrarySignIn(
    outcome: LibraryService.StoredSignIn,
    session: LibrarySession,
    publishLibraryCredentialsToWatch: suspend () -> Unit,
) {
    session.refresh()
    if (outcome == LibraryService.StoredSignIn.REFUSED) publishLibraryCredentialsToWatch()
}
