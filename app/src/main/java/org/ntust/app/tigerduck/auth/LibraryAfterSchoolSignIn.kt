package org.ntust.app.tigerduck.auth

/**
 * The library sign-in that follows the school's typed sign-in, with the same account, while no
 * library token is valid and no library account of another student ID can still sign in by
 * itself: a school sign-in keeps such an account, as a school sign-out does (as on the iPhone,
 * tigerduck-app/tigerduck-app#542). With none, a refused one, or this student's own with its token
 * run out, the school credentials sign in. Apart from [AuthService] so a test can run it.
 *
 * It reads the school account and the library account generations under [accountLock], while
 * [signInGeneration] is still the one read before the sign-in began. A school sign-out, which
 * moves that generation and the school account's while it holds the lock, then either came first,
 * and nothing signs in, or comes after, and [login] stores nothing once it has ([isSchoolAccount]).
 * A library account it stored before that sign-out is this student's, which the sign-out takes
 * with it. The sign-in is one the app makes on its own, so it also yields to a library account the
 * user signed in to meanwhile.
 */
internal class LibraryAfterSchoolSignIn(
    private val tokenValid: () -> Boolean,
    /** Whether a library account of another student ID than the one given can still sign in by itself. */
    private val hasAccountOtherThan: (studentId: String) -> Boolean,
    /** Moved by each school sign-out; see [org.ntust.app.tigerduck.data.preferences.CredentialManager.schoolAccountGeneration]. */
    private val schoolAccount: () -> Int,
    /** Whether no school sign-out has come since the [schoolAccount] given was read. */
    private val isSchoolAccount: (Int) -> Boolean,
    /** [org.ntust.app.tigerduck.shared.LibraryCredentialStore.libraryAccountGeneration]. */
    private val libraryAccount: () -> Int,
    /**
     * Signs in and stores the library's credentials and token, unless the library account has
     * moved since [libraryAccount] was read or `stillCurrent` is false as it stores; see
     * [org.ntust.app.tigerduck.shared.LibraryService.login].
     */
    private val login: suspend (
        studentId: String,
        password: String,
        libraryAccount: Int,
        stillCurrent: () -> Boolean,
    ) -> Unit,
    private val signInGeneration: () -> Int,
    private val accountLock: Any,
) {
    /** Signs in unless one of the above holds; [generation] was read before the sign-in. */
    suspend fun signIn(studentId: String, password: String, generation: Int) {
        // The school account first, as the school sign-in always read it.
        val (school, library) = synchronized(accountLock) {
            if (signInGeneration() != generation) return
            schoolAccount() to libraryAccount()
        }
        if (tokenValid() || hasAccountOtherThan(studentId)) return
        login(studentId, password, library) { isSchoolAccount(school) }
    }
}
