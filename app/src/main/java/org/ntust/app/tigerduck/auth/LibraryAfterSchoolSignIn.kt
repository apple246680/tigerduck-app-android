package org.ntust.app.tigerduck.auth

/**
 * The library sign-in that follows the school's, with the same account, for the sign-in the user
 * types and the automatic one alike. Apart from [AuthService] so a test can run it.
 *
 * [login] signs in and stores the library's credentials and token. A sign-out while it runs, which
 * moves [signInGeneration] from the generation read before the sign-in began while it holds
 * [accountLock], has them dropped again ([dropLibrary]): the next account finds none of the
 * departed one's.
 */
internal class LibraryAfterSchoolSignIn(
    private val tokenValid: () -> Boolean,
    private val login: suspend (studentId: String, password: String) -> Unit,
    private val signInGeneration: () -> Int,
    private val accountLock: Any,
    private val dropLibrary: () -> Unit,
) {
    /** Signs in unless the library token is still good; [generation] was read before the sign-in. */
    suspend fun signIn(studentId: String, password: String, generation: Int) {
        if (tokenValid()) return
        login(studentId, password)
        // Stored after a sign-out cleared the library: what it stored is the departed account's.
        synchronized(accountLock) { if (signInGeneration() != generation) dropLibrary() }
    }
}
