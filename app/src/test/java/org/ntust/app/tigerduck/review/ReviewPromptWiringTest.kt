package org.ntust.app.tigerduck.review

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where [ReviewPrompt] is told about the class table (tigerduck-app-android#332).
 * The two view models take some twenty collaborators each and cannot be built
 * in a JVM test, so what this pins is that they still make the calls; read
 * from the source, as ClassTableWiringTest does.
 */
class ReviewPromptWiringTest {

    private val classTable = "src/main/java/org/ntust/app/tigerduck/ui/screen/classtable/ClassTableViewModel.kt"
    private val home = "src/main/java/org/ntust/app/tigerduck/ui/screen/home/HomeViewModel.kt"

    private fun source(rel: String): List<String> {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, rel).let { if (it.isFile) return it.readLines() }
            File(dir, "app/$rel").let { if (it.isFile) return it.readLines() }
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate $rel from ${File("").absolutePath}")
    }

    /** Member function [name] of the file at [rel], up to the next member or doc comment. */
    private fun body(rel: String, name: String): String {
        val lines = source(rel)
        val start = lines.indexOfFirst {
            Regex("""^ {4}(?:(?:private|internal|override|suspend) )*fun $name\(""").containsMatchIn(it)
        }
        if (start < 0) throw AssertionError("$rel has no fun $name")
        val next = Regex("""^ {4}(?:(?:private|internal|override|suspend) )*(?:fun|val|var) |^ {4}/\*\*|^}""")
        val end = (start + 1 until lines.size).firstOrNull { next.containsMatchIn(lines[it]) } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    private fun assertCountsAfterSaving(rel: String, save: String) {
        val body = body(rel, "fetchData")
        val saved = body.indexOf(save)
        val counted = body.indexOf("reviewPrompt.classTableLoaded()")
        assertTrue("$rel: fetchData no longer counts a day of use once it has saved the fetched courses", saved in 0 until counted)
    }

    @Test
    fun `a fetch counts as a day of use once the class table's courses are saved`() {
        assertCountsAfterSaving(classTable, "dataCache.saveCourses(merged, semester)")
        assertCountsAfterSaving(home, "dataCache.saveCourses(courses)")
    }

    @Test
    fun `a class table fetch that failed is reported, so the ask waits`() {
        val body = body(classTable, "fetchData")
        assertTrue("fetchData no longer reports a failed fetch", "reviewPrompt.loadFailed()" in body)
    }

    @Test
    fun `a return to Home or the class table is a moment to ask at`() {
        for (rel in listOf(classTable, home)) {
            for (name in listOf("onPageShown", "onAppForeground")) {
                assertTrue("$rel: $name no longer offers a calm moment", "reviewPrompt.calmMoment()" in body(rel, name))
            }
        }
    }
}
