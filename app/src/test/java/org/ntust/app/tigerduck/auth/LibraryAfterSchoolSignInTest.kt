package org.ntust.app.tigerduck.auth

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The library sign-in after the school's, and a sign-out that lands while it runs. */
class LibraryAfterSchoolSignInTest {

    /** The library credentials stored, as the student ID they are for. */
    private var library: String? = null
    private var generation = 0
    private val accountLock = Any()
    private var tokenValid = false
    private var logins = 0

    /** Runs as the library answers, after it stored what it got. */
    private var onLogin: () -> Unit = {}

    /** What AuthService.logout does to the library credentials and the generation. */
    private fun signOut() = synchronized(accountLock) {
        library = null
        generation++
    }

    private fun librarySignIn() = LibraryAfterSchoolSignIn(
        tokenValid = { tokenValid },
        login = { studentId, _ ->
            logins++
            library = studentId
            onLogin()
        },
        signInGeneration = { generation },
        accountLock = accountLock,
        dropLibrary = { library = null },
    )

    @Test
    fun `the library signs in with the account and keeps what it got`() = runTest {
        librarySignIn().signIn(STUDENT, "password", generation)

        assertEquals(STUDENT, library)
    }

    @Test
    fun `a sign-out while the library signs in leaves none of its credentials`() = runTest {
        val before = generation
        // The sign-out clears the library first; the library's answer then stores the departed
        // account's credentials, as LibraryService.login does once its reply arrives.
        onLogin = {
            signOut()
            library = STUDENT
        }

        librarySignIn().signIn(STUDENT, "password", before)

        assertNull("the next account finds none of the departed one's", library)
    }

    @Test
    fun `a library token still good signs in nothing`() = runTest {
        tokenValid = true

        librarySignIn().signIn(STUDENT, "password", generation)

        assertEquals(0, logins)
    }

    private companion object {
        const val STUDENT = "B00000000"
    }
}
