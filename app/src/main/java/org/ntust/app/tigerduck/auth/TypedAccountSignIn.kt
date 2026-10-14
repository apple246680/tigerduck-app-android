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
 * nothing by itself (SsoLoginService.signIn).
 *
 * [signInToLibrary] comes next, before the account shows as signed in: the paired watch is sent
 * the library credentials the moment it does. The demo account ([demoSignIn]) signs in on the
 * device, stored the same way, with nothing sent anywhere.
 */
internal class TypedAccountSignIn(
    private val schoolSignIn: suspend (studentId: String, password: String) -> Boolean,
    private val signInInteractively: suspend (
        signIn: suspend (signInGeneration: Int) -> SignInAttempt,
    ) -> Boolean,
    private val storedStudentId: () -> String?,
    private val writeAccount: (studentId: String, password: String) -> Unit,
    private val latch: StoredPasswordLatch,
    /** Signs in on the device when these are the demo account's, and says whether they were. */
    private val demoSignIn: suspend (studentId: String, password: String) -> Boolean,
    private val signInToLibrary: suspend (studentId: String, password: String) -> Unit,
) {
    enum class Outcome {
        /** The school did not take the credentials. */
        NOT_SIGNED_IN,

        /** Signed in with the school. */
        SIGNED_IN,

        /** The demo account, signed in on the device. */
        DEMO,
    }

    /**
     * Signs in as [studentId] with [password]. [showSignedIn] makes the account show as signed in;
     * [afterShown] runs the rest, with the generation read before the store and whether the
     * account stored before was this one.
     */
    suspend fun signIn(
        studentId: String,
        password: String,
        showSignedIn: suspend () -> Unit,
        afterShown: suspend (signInGeneration: Int, sameStudent: Boolean) -> SignInAttempt,
    ): Outcome {
        // Checked before anything goes out, so these credentials never reach NTUST.
        if (demoSignIn(studentId, password)) {
            storeAccount(studentId, password)
            showSignedIn()
            return Outcome.DEMO
        }
        if (!schoolSignIn(studentId, password)) return Outcome.NOT_SIGNED_IN
        signInInteractively { signIn ->
            // Read before it is overwritten: a stored Moodle token is this student's only if
            // the account stored was.
            val sameStudent = storedStudentId() == studentId
            storeAccount(studentId, password)
            // Cancelled from here on, the sign-in still leaves the password the school took,
            // with no refusal of the old one held against it.
            signInToLibrary(studentId, password)
            showSignedIn()
            afterShown(signIn, sameStudent)
        }
        return Outcome.SIGNED_IN
    }

    /**
     * Keeps [studentId] and [password] as the stored account and lifts the stored-password latch,
     * as one step: a refusal it held was of the password before.
     */
    private fun storeAccount(studentId: String, password: String) {
        writeAccount(studentId, password)
        latch.clear()
    }
}
