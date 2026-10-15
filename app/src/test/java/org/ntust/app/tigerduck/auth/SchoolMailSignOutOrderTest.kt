package org.ntust.app.tigerduck.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The user's own sign-out takes a school mail account signed in as the same student with it. The
 * mail account is matched by the stored student ID, so [AuthService.logout] has to ask before
 * `signOutSchoolAccount` clears the school credentials; asked afterwards, there is no ID left and
 * the mail account stays.
 * AuthService needs the Android Keystore, so this reads its source, as
 * ImportedClassTablesClearingTest does; MailAccountTest covers the same and the other student.
 */
class SchoolMailSignOutOrderTest {

    private val source: String = File(appDir(), "src/main/java/org/ntust/app/tigerduck/auth/AuthService.kt").readText()

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

    private val mailSignOut = Regex("""\bmailAccount\.signOutIfSchoolAccount\(""")
    private val schoolClear = Regex("""\bcredentials\.signOutSchoolAccount\(\)""")

    @Test
    fun `the sign-out signs the school mail out with the stored student ID`() {
        val logout = body("fun logout()")
        assertEquals(1, mailSignOut.findAll(logout).count())
        assertTrue(Regex("""credentials\.ntustStudentId\?\.let \{ mailAccount\.signOutIfSchoolAccount\(it\) \}""").containsMatchIn(logout))
    }

    @Test
    fun `the school mail goes before the school credentials are cleared`() {
        val logout = body("fun logout()")
        val mail = mailSignOut.find(logout) ?: throw AssertionError("logout() never signs the school mail out")
        val clear = schoolClear.find(logout) ?: throw AssertionError("logout() never clears the school credentials")
        assertTrue("the mail sign-out runs after the student ID is gone", mail.range.first < clear.range.first)
    }
}
