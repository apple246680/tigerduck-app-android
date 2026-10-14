package org.ntust.app.tigerduck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Release builds keep Log.w and Log.e (ReleaseLogRulesTest), and logcat hands
 * them to adb and to every bug report. So a warning or an error names a
 * failure by its class, with `logSummary()` or `javaClass.simpleName`, and
 * carries no course number, id, token or server text: an exception's message,
 * and so its stack trace, can quote a server's reply.
 *
 * This reads every Log.w, Log.e and Log.wtf call in the code the phone and the
 * watch ship and fails on the shapes that let those through.
 */
class ReleaseLogLinesTest {

    /** Sources that reach a release build: the phone's flavors, :shared, and the watch. */
    private val shipped = listOf("src/main", "src/play", "src/fdroid", "../shared/src/main", "../wear/src/main")

    @Test
    fun `no warning or error in shipped code logs an exception's text or user data`() {
        val calls = shipped.map(::File).filter { it.isDirectory }
            .flatMap { dir -> dir.walk().filter { it.isFile && it.extension == "kt" } }
            .flatMap { file -> LogCallScanner(file.readText()).calls().map { file.path to it } }
        // A scanner that stopped finding calls would pass everything.
        assertTrue("found only ${calls.size} calls", calls.size > 100)
        val violations = calls.flatMap { (path, call) -> call.violations().map { "$path:${call.line}: $it" } }
        assertEquals(emptyList<String>(), violations)
    }

    // --- the scanner itself, on the shapes release logs used to have ----------------------

    private fun violations(source: String) = LogCallScanner(source).calls().flatMap { it.violations() }

    @Test
    fun `a throwable passed after the message is caught`() {
        assertEquals(1, violations("""Log.w(TAG, "register failed", e)""").size)
        assertEquals(1, violations("""android.util.Log.e("Tag", "x: ${'$'}{e.logSummary()}", e)""").size)
    }

    @Test
    fun `a throwable passed as the message is caught`() {
        assertEquals(1, violations("""Log.w(TAG, e)""").size)
    }

    @Test
    fun `an exception's text in the message is caught`() {
        listOf(
            """Log.w(TAG, "failed: ${'$'}e")""",
            """Log.w(TAG, "failed: ${'$'}{e.message}")""",
            """Log.w(TAG, "failed: ${'$'}{it.localizedMessage}")""",
            """Log.w(TAG, "failed: ${'$'}{t.stackTraceToString()}")""",
            """Log.w(TAG, "failed: ${'$'}{error}")""",
            """Log.w(TAG, "failed: " + e.message)""",
            """Log.e(TAG, "failed: " + e)""",
        ).forEach { assertEquals(it, 1, violations(it).size) }
    }

    @Test
    fun `course numbers, ids and secrets in the message are caught`() {
        listOf(
            """Log.w(TAG, "color sync failed for ${'$'}courseNo")""",
            """Log.w(TAG, "PATCH ${'$'}{course.courseNo} failed")""",
            """Log.w(TAG, "PATCH ${'$'}courseKey failed")""",
            """Log.w(TAG, "wrote score_${'$'}studentId.json")""",
            """Log.w(TAG, "override ${'$'}assignmentId failed")""",
            """Log.w(TAG, "device ${'$'}deviceId")""",
            """Log.w(TAG, "moodle ${'$'}{c.moodleIdNumber}")""",
            """Log.w(TAG, "token ${'$'}{prefs.accessToken}")""",
            """Log.w(TAG, "reply ${'$'}{response.body}")""",
        ).forEach { assertEquals(it, 1, violations(it).size) }
    }

    @Test
    fun `a failure named by its class and status passes`() {
        val source = """
            Log.w(TAG, "register failed: ${'$'}{e.logSummary()}")
            Log.w(TAG, "QR refresh failed: ${'$'}{e.javaClass.simpleName}")
            Log.e(TAG, "retry failed: ${'$'}{retry::class.java.simpleName}")
            Log.w(TAG, "refresh got HTTP ${'$'}{response.code}; keeping the tokens")
            android.util.Log.w(
                "CalendarViewModel", "holiday override upload failed: ${'$'}{e.logSummary()}",
            )
            Log.w(TAG, "${'$'}label '${'$'}name' exceeds ${'$'}maxChars chars")
            Log.w(TAG, message)
        """
        val calls = LogCallScanner(source).calls()
        assertEquals(7, calls.size)
        assertEquals(emptyList<String>(), calls.flatMap { it.violations() })
    }

    @Test
    fun `calls are found across lines, and never in comments or strings`() {
        val source = """
            // Log.w(TAG, "old", e)
            /* Log.e(TAG, "old", e) /* nested */ Log.e(TAG, e) */
            val doc = "Log.w(TAG, e)"
            val quote = '"'
            Log.w(
                TAG,
                "a ${'$'}{listOf(1, 2).joinToString(", ") { "(${'$'}it)" }} b",
                e,
            )
        """
        val calls = LogCallScanner(source).calls()
        assertEquals(1, calls.size)
        assertEquals(6, calls.single().line)
        assertEquals(1, calls.single().violations().size)
    }
}

/**
 * Finds `Log.w(`, `Log.e(` and `Log.wtf(` calls in Kotlin source, skipping
 * comments, strings and character literals, and splits each call into its
 * top-level arguments.
 */
private class LogCallScanner(private val s: String) {

    class Arg(
        /** The argument with each string literal replaced by `S`. */
        val code: String,
        /** What the argument's string templates interpolate, `$x` and `${x}` alike. */
        val templates: List<String>,
    )

    class LogCall(val line: Int, val args: List<Arg>) {
        fun violations(): List<String> {
            val problems = mutableListOf<String>()
            if (args.size != 2) {
                problems += "passes ${args.size} arguments; a throwable after the message prints its message and stack trace"
            }
            val message = args.getOrNull(1) ?: return problems
            // Templates, and whatever the message adds to its literals with `+`.
            val expressions = message.templates +
                message.code.split('+').map { it.trim() }.filter { it.isNotEmpty() && it != "S" }
            expressions.forEach { expr ->
                val root = expr.takeWhile { it.isLetterOrDigit() || it == '_' }
                when {
                    root in THROWABLE_NAMES && !NAMED_BY_CLASS.matches(expr) ->
                        problems += "logs `$expr`; name the failure with logSummary() or javaClass.simpleName"
                    SENSITIVE.containsMatchIn(expr) ->
                        problems += "logs `$expr`, which can carry user data or server text"
                }
            }
            return problems
        }
    }

    private var i = 0

    fun calls(): List<LogCall> {
        val calls = mutableListOf<LogCall>()
        while (i < s.length) {
            val name = CALLS.firstOrNull { s.startsWith(it, i) }
            when {
                skipCommentOrLiteral() -> Unit
                name != null && (i == 0 || !s[i - 1].isJavaIdentifierPart()) -> {
                    val line = s.substring(0, i).count { it == '\n' } + 1
                    i += name.length
                    calls += LogCall(line, arguments())
                }
                else -> i++
            }
        }
        return calls
    }

    /** Skips a comment, string, character literal or backticked name at [i]; false if there is none. */
    private fun skipCommentOrLiteral(): Boolean {
        when {
            s.startsWith("//", i) -> i = s.indexOf('\n', i).let { if (it < 0) s.length else it }
            s.startsWith("/*", i) -> {
                var depth = 0
                do {
                    when {
                        s.startsWith("/*", i) -> { depth++; i += 2 }
                        s.startsWith("*/", i) -> { depth--; i += 2 }
                        else -> i++
                    }
                } while (depth > 0 && i < s.length)
            }
            s[i] == '"' -> string()
            s[i] == '\'' -> {
                i++
                while (i < s.length && s[i] != '\'') i += if (s[i] == '\\') 2 else 1
                i++
            }
            s[i] == '`' -> i = s.indexOf('`', i + 1).let { if (it < 0) s.length else it + 1 }
            else -> return false
        }
        return true
    }

    /** Reads a string literal starting at its opening quote; returns its template expressions. */
    private fun string(): List<String> {
        val raw = s.startsWith("\"\"\"", i)
        i += if (raw) 3 else 1
        val templates = mutableListOf<String>()
        while (i < s.length) {
            when {
                raw && s.startsWith("\"\"\"", i) -> {
                    i += 3
                    while (i < s.length && s[i] == '"') i++
                    return templates
                }
                !raw && s[i] == '"' -> { i++; return templates }
                !raw && s[i] == '\\' -> i += 2
                s.startsWith("\${", i) -> {
                    val start = i + 2
                    i = start
                    code(until = '}')
                    templates += s.substring(start, i - 1).trim()
                }
                s[i] == '$' && i + 1 < s.length && (s[i + 1].isLetter() || s[i + 1] == '_') -> {
                    val start = ++i
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                    templates += s.substring(start, i)
                }
                else -> i++
            }
        }
        return templates
    }

    /** Skips code up to and past the [until] that closes it, minding nesting and literals. */
    private fun code(until: Char) {
        var depth = 0
        while (i < s.length) {
            if (skipCommentOrLiteral()) continue
            val c = s[i++]
            when {
                c in "([{" -> depth++
                c in ")]}" && depth > 0 -> depth--
                c == until -> return
            }
        }
    }

    /** Reads a call's arguments, [i] just past its opening parenthesis, and steps past the closing one. */
    private fun arguments(): List<Arg> {
        val args = mutableListOf<Arg>()
        var code = StringBuilder()
        var templates = mutableListOf<String>()
        var depth = 0
        fun endArg() {
            if (code.isNotBlank()) args += Arg(code.toString().trim(), templates)
            code = StringBuilder()
            templates = mutableListOf()
        }
        while (i < s.length) {
            when {
                s[i] == '"' -> { templates += string(); code.append('S') }
                skipCommentOrLiteral() -> Unit
                s[i] in "([{" -> { depth++; code.append(s[i++]) }
                s[i] in ")]}" && depth == 0 -> { i++; endArg(); return args }
                s[i] in ")]}" -> { depth--; code.append(s[i++]) }
                s[i] == ',' && depth == 0 -> { i++; endArg() }
                else -> code.append(s[i++])
            }
        }
        return args
    }

    companion object {
        val CALLS = listOf("Log.wtf(", "Log.w(", "Log.e(")

        /** What a catch block or a failure lambda calls the exception in this codebase. */
        val THROWABLE_NAMES = setOf("e", "t", "it", "ex", "error", "exception", "throwable", "cause", "retry", "failure")

        val NAMED_BY_CLASS = Regex("""\w+(\.logSummary\(\)|\.javaClass\.simpleName|::class\.java\.simpleName)""")

        val SENSITIVE = Regex(
            """course_?no|course_?key|student_?id|assignment_?id|device_?id|moodle_?id|token|password|""" +
                """\.message\b|localizedmessage|stacktrace|\bbody\b""",
            RegexOption.IGNORE_CASE,
        )
    }
}
