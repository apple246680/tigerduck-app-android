package org.ntust.app.tigerduck.auth

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Where AuthService lifts the stored-password latch: on sign-out, as the refusal belonged to the
 * password leaving with the account, and as soon as a sign-in stores a new password, so nothing
 * between the store and the end of the sign-in still holds that password's refusal against it.
 * AuthService needs the Android Keystore, which no JVM test can open, so this reads its source,
 * as ImportedClassTablesClearingTest does; StoredPasswordLatchTest covers what the call does.
 */
class AuthServiceLatchClearingTest {

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
                '}' -> if (--depth == 0) return source.substring(open + 1, index)
            }
        }
        throw AssertionError("`$declaration` has no closing brace")
    }

    @Test
    fun `the sign-out lifts the latch`() {
        assertEquals(1, Regex("""\bstoredPasswordLatch\.clear\(\)""").findAll(body("fun logout()")).count())
    }

    @Test
    fun `each sign-in lifts it right after it stores the new password`() {
        val lines = body("suspend fun login(").lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") }
        val stores = lines.indices.filter { lines[it] == "credentials.ntustPassword = password" }

        // The demo account's sign-in and the school's.
        assertEquals(2, stores.size)
        stores.forEach { assertEquals("storedPasswordLatch.clear()", lines[it + 1]) }
    }
}
