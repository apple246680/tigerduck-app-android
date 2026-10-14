package org.ntust.app.tigerduck.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the user's own sign-out leaves for the next student. AuthService needs the Android
 * Keystore, so this reads [AuthService.logout]'s source, as ImportedClassTablesClearingTest does.
 */
class SignOutClearingTest {

    private val source: String =
        File(appDir(), "src/main/java/org/ntust/app/tigerduck/auth/AuthService.kt").readText()

    private fun appDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val here = dir
            if (File(here, "app/src/main/java").isDirectory) return File(here, "app")
            if (File(here, "src/main/java").isDirectory) return here
            dir = here.parentFile
        }
        throw AssertionError("Could not locate the :app module from ${File("").absolutePath}")
    }

    /** The body of the function [declaration] starts, up to its matching brace. */
    private fun body(declaration: String): String {
        val start = source.indexOf(declaration)
        if (start < 0) throw AssertionError("AuthService has no `$declaration`")
        val open = source.indexOf('{', start)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) {
                    return source.substring(open + 1, index).also {
                        assertFalse("`$declaration` ran into the next function", Regex("""\n {4}\S""").containsMatchIn(it))
                    }
                }
            }
        }
        throw AssertionError("`$declaration` has no closing brace")
    }

    @Test
    fun `the sign-out forgets the term picked in the class table`() {
        // Left behind, the next student's class table would open on the previous student's term.
        assertEquals(1, Regex("""\bprefs\.classTableSelectedSemester = null\b""").findAll(body("fun logout()")).count())
    }
}
