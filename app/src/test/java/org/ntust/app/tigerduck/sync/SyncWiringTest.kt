package org.ntust.app.tigerduck.sync

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where the sync's tested rules are called. The rules have their own tests;
 * what these pin is that the classes that cannot be built in a JVM test (the
 * view models, the worker, the backend clients) still call them, so taking a
 * call out fails here. Read from the source, as
 * `ClassTableViewModelInitOrderTest` does.
 */
class SyncWiringTest {

    private fun source(rel: String): List<String> {
        val path = "src/main/java/org/ntust/app/tigerduck/$rel"
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            File(dir, path).let { if (it.isFile) return it.readLines() }
            File(dir, "app/$path").let { if (it.isFile) return it.readLines() }
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate $path from ${File("").absolutePath}")
    }

    /** Member function [name] of [rel], up to the next member, doc comment or the class's end. */
    private fun body(rel: String, name: String): String {
        val lines = source(rel)
        val start = lines.indexOfFirst {
            Regex("""^ {4}(?:(?:private|internal|override|suspend) )*fun $name\(""").containsMatchIn(it)
        }
        if (start < 0) throw AssertionError("$rel has no member fun $name")
        val next = Regex("""^ {4}(?:(?:private|internal|override|suspend|@\w+) )*(?:fun|val|var|init) |^ {4}/\*\*|^}""")
        val end = (start + 1 until lines.size).firstOrNull { next.containsMatchIn(lines[it]) } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    private fun assertCalls(rel: String, name: String, vararg calls: String) {
        val body = body(rel, name)
        for (call in calls) assertTrue("$rel $name no longer calls $call", call in body)
    }

    @Test
    fun `Home's sync and the worker hold colours and names for their re-enable checks`() {
        assertCalls(
            "ui/screen/home/HomeBackendSync.kt", "pull",
            "CourseSyncReconciler.syncsCourseColors(", "CourseSyncReconciler.syncsCourseNames(",
        )
        assertCalls("notification/BackgroundSyncWorker.kt", "syncOverridesFromBackend", "CourseSyncReconciler.syncsCourseColors(")
    }

    @Test
    fun `Home's sync records only the categories it took`() {
        assertCalls("ui/screen/home/HomeBackendSync.kt", "pull", "categoriesTaken(", "categoriesSynced(switchedOn, took, prefs)")
    }

    @Test
    fun `an answered re-enable dialog holds colours and names until applied`() {
        assertCalls(
            "ui/screen/settings/SettingsViewModel.kt", "resolveReenableConflict",
            "heldUntilAnswered(", "prefs.pendingConflictCategories = held", "prefs.pendingConflictCategories - held",
        )
    }

    @Test
    fun `a registration crossing the TigerSync switch registers again`() {
        assertCalls("push/PushRegistrationService.kt", "endRegistration", "leftSwitchBehind(appPreferences.cloudSyncEnabled)")
        assertCalls(
            "push/PushRegistrationService.kt", "updateCloudSyncEnabled",
            "switchPatches.send(request, enabled)", "leftSwitchBehind(appPreferences.cloudSyncEnabled)",
        )
    }

    @Test
    fun `the assignment upload goes through the ledger`() {
        assertCalls("push/PushApiClient.kt", "uploadAssignments", "assignmentUploads.sendIfDue(")
    }

    @Test
    fun `a full sync and a poll that reaches the server run an owed re-enable check`() {
        assertCalls("ui/screen/home/HomeBackendSync.kt", "pull", "reenableCheck.runIfOwed()")
        assertCalls("ui/screen/home/HomeViewModel.kt", "pollRevision", "reenableCheck.runIfOwed()")
    }

    @Test
    fun `the closed assignments are written only for the account that asked`() {
        val body = body("network/MoodleService.kt", "fetchAssignmentsRound")
        val guard = body.indexOf("if (tokenService.currentToken() == askedWith && closedNow != closedBefore) {")
        val save = body.indexOf("dataCache.saveClosedAssignments(closedNow)")
        assertTrue("the closed assignments are no longer written behind the token check", guard in 0 until save)
        assertTrue(
            "nothing else between the check and the write",
            body.substring(guard, save).count { it == '\n' } == 1,
        )
    }
}
