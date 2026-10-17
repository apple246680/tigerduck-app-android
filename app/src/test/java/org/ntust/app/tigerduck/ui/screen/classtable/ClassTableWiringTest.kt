package org.ntust.app.tigerduck.ui.screen.classtable

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where the class table's tested rules are called. The view model takes some twenty
 * collaborators and cannot be built in a JVM test, so what these pin is that it still calls
 * them; read from the source, as [ClassTableViewModelInitOrderTest] does.
 */
class ClassTableWiringTest {

    private val rel = "src/main/java/org/ntust/app/tigerduck/ui/screen/classtable/ClassTableViewModel.kt"

    private fun source(): List<String> {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, rel).let { if (it.isFile) return it.readLines() }
            File(dir, "app/$rel").let { if (it.isFile) return it.readLines() }
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate ClassTableViewModel.kt from ${File("").absolutePath}")
    }

    /** Member function [name], up to the next member or doc comment. */
    private fun body(name: String): String {
        val lines = source()
        val start = lines.indexOfFirst {
            Regex("""^ {4}(?:(?:private|internal|override|suspend) )*fun $name\(""").containsMatchIn(it)
        }
        if (start < 0) throw AssertionError("ClassTableViewModel has no fun $name")
        val next = Regex("""^ {4}(?:(?:private|internal|override|suspend) )*(?:fun|val|var) |^ {4}/\*\*|^}""")
        val end = (start + 1 until lines.size).firstOrNull { next.containsMatchIn(lines[it]) } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    private fun assertCalls(name: String, vararg calls: String) {
        val body = body(name)
        for (call in calls) assertTrue("$name no longer calls $call", call in body)
    }

    /** tigerduck-app-android#241. */
    @Test
    fun `a reset goes through the backend first, and says so when the backend could not take it`() {
        assertCalls("wipeTerm", "termReset.run(semester) { pushApiClient.deleteAllCourses(semester) }")
        assertCalls("resetCourses", "if (wiped) fetchData() else _resetFailedEvent.tryEmit(semester)")
    }

    /** tigerduck-app-android#347: an add landing after the DELETE would bring the course back. */
    @Test
    fun `a delete and a reset reach the backend with no course add being sent`() {
        for ((name, call) in listOf(
            "deleteCourse" to "pushApiClient.deleteCourse(courseKey)",
            "wipeTerm" to "termReset.run(semester)",
        )) {
            val body = body(name)
            val held = body.indexOf("pendingCourseAdds.removing {")
            assertTrue("$name no longer holds the course adds back over $call", held in 0 until body.indexOf(call))
        }
    }

    /** tigerduck-app-android#380: sent again later, a waiting colour or name would bring a row the user removed back. */
    @Test
    fun `a deleted course, and a reset term, take their waiting colours and names with them`() {
        assertCalls("deleteCourse", "pendingCourseOverrides.forget(")
        val wipe = body("wipeTerm")
        val reset = wipe.indexOf("termReset.run(semester)")
        val forgot = wipe.indexOf("pendingCourseOverrides.forget(")
        assertTrue("wipeTerm no longer forgets the term's waiting colours and names after the reset", forgot > reset)
    }

    /** tigerduck-app-android#242. */
    @Test
    fun `the page counts as settled once the first load's fetch is done`() {
        val body = body("loadNow")
        val fetch = body.indexOf("fetchFlight.join(::fetchData)")
        val settled = body.indexOf("_hasSettled.value = true")
        assertTrue("loadNow no longer marks the first load settled after its fetch", fetch in 0 until settled)
    }

    /** tigerduck-app-android#243. */
    @Test
    fun `a selection opens on its block`() {
        assertCalls("selectCourse", "_selectedPeriods.value = periods ?: ClassTableSelection.block(")
    }

    /** tigerduck-app-android#264: mergeFetched keeps hand-added rows as stored, so a fetch looks them up first. */
    @Test
    fun `a fetch looks the term's hand-added courses up again before the merge`() {
        assertCalls("fetchData", "fetchTermCourses(semester, orderedCourseNos, moodleByNo)")
        val body = body("fetchTermCourses")
        val lookedUp = body.indexOf("ClassTableCourseMerge.manualNosToLookUp(cached, rosterNos)")
        val refreshed = body.indexOf("ClassTableCourseMerge.withLookedUp(cached,")
        val merged = body.indexOf("ClassTableCourseMerge.mergeFetched(")
        assertTrue("fetchTermCourses no longer looks the hand-added courses up", lookedUp in 0 until refreshed)
        assertTrue("fetchTermCourses no longer merges over the refreshed rows", refreshed in 0 until merged)
    }

    /** tigerduck-app-android#264: rooms are announced late, and the page fetches on launch only. */
    @Test
    fun `each visit looks the courses without a room up again, and a hit reaches the widget`() {
        assertCalls("onPageShown", "lookUpMissingRooms()")
        assertCalls(
            "lookUpMissingRooms",
            "ClassTableCourseMerge.nosWithoutRoom(",
            "ClassTableCourseMerge.withRoomsFound(",
            "dataCache.saveCourses(",
            "widgetUpdater.requestUpdate()",
        )
    }

    /** tigerduck-app-android#265. */
    @Test
    fun `the first fetch of a session warms the other terms, and a sign-out ends the warm`() {
        val fetch = body("fetchData")
        val synced = fetch.indexOf("_syncCompleteEvent.tryEmit(Unit)")
        val warmed = fetch.indexOf("warmTerms(")
        assertTrue("fetchData no longer warms the other terms once its own is done", synced in 0 until warmed)
        assertCalls("warmTerms", "termWarm.run {")
        assertCalls("warmTerm", "fetchTermCourses(semester, rosterNos, moodleByNo)")
        val source = source().joinToString("\n")
        for (call in listOf("warmJob?.cancel()", "termWarm.reset()")) {
            assertTrue("a sign-out no longer calls $call", call in source)
        }
    }
}
