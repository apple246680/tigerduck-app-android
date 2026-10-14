package org.ntust.app.tigerduck.auth

import org.ntust.app.tigerduck.auth.AuthTokenManager.SignInAttempt

/**
 * The sign-in with credentials the user typed, which [AuthService] runs as its login. Apart from
 * it so a test can run it: AuthService needs the Android Keystore.
 *
 * [schoolSignIn] posts the credentials to the school. Once the school takes them,
 * [signInInteractively] runs the rest as the sign-in under way, and the first thing it does is
 * [storeAccount]: the credentials become the stored account and the stored-password latch lifts.
 * Nothing that can suspend comes between the school's answer and that store, so a sign-in
 * cancelled at any point leaves either the old password with its refusal latched or the new one
 * without it, never the refused password stored and unlatched: the school's acceptance lifts
 * nothing by itself (SsoLoginService.signIn). [afterStored] runs the rest, the library's sign-in
 * among it.
 */
internal class TypedAccountSignIn(
    private val schoolSignIn: suspend (studentId: String, password: String) -> Boolean,
    private val signInInteractively: suspend (
        signIn: suspend (signInGeneration: Int) -> SignInAttempt,
    ) -> Boolean,
    private val storedStudentId: () -> String?,
    private val writeAccount: (studentId: String, password: String) -> Unit,
    private val latch: StoredPasswordLatch,
) {
    /**
     * Signs in as [studentId] with [password], and says whether the school took them.
     * [afterStored] gets the generation read before the store, and whether the account stored
     * before was this one.
     */
    suspend fun signIn(
        studentId: String,
        password: String,
        afterStored: suspend (signInGeneration: Int, sameStudent: Boolean) -> SignInAttempt,
    ): Boolean {
        if (!schoolSignIn(studentId, password)) return false
        signInInteractively { signIn ->
            // Read before it is overwritten: a stored Moodle token is this student's only if
            // the account stored was.
            val sameStudent = storedStudentId() == studentId
            storeAccount(studentId, password)
            afterStored(signIn, sameStudent)
        }
        return true
    }

    /**
     * Keeps [studentId] and [password] as the stored account and lifts the stored-password latch,
     * as one step: a refusal it held was of the password before.
     */
    fun storeAccount(studentId: String, password: String) {
        writeAccount(studentId, password)
        latch.clear()
    }
}
