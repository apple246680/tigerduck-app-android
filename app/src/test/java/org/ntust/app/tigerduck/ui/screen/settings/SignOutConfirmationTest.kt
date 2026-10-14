package org.ntust.app.tigerduck.ui.screen.settings

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.sharing.ClassTableShareCode
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableStore
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.screen.settings.SignOutLoss.IMPORTED_CLASS_TABLES
import org.ntust.app.tigerduck.ui.screen.settings.SignOutLoss.UNSYNCED_DATA
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Signing out deletes what exists only on the phone, so it asks first and says what goes: the
 * edits TigerSync does not keep (always, on fdroid), and the imported class tables, which the
 * iPhone app asks about.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SignOutConfirmationTest {

    private val allSynced = SyncedKinds(enabled = true, courses = true, courseNames = true, courseColours = true, assignments = true)
    private val syncOff = allSynced.copy(enabled = false)

    @Test
    fun `nothing of the user's own signs out at once`() {
        assertEquals(emptySet<SignOutLoss>(), signOutLosses(LocalEdits(), syncOff, hasImportedClassTables = false))
    }

    @Test
    fun `with TigerSync off, every kind of edit is asked about`() {
        listOf(
            LocalEdits(courses = true),
            LocalEdits(courseNames = true),
            LocalEdits(courseColours = true),
            LocalEdits(assignmentMarks = true),
        ).forEach { edits ->
            assertEquals("$edits", setOf(UNSYNCED_DATA), signOutLosses(edits, syncOff, hasImportedClassTables = false))
        }
    }

    @Test
    fun `edits TigerSync keeps are not asked about, one whose switch is off is`() {
        val edits = LocalEdits(courses = true, courseColours = true, assignmentMarks = true)

        assertEquals(emptySet<SignOutLoss>(), signOutLosses(edits, allSynced, hasImportedClassTables = false))
        assertEquals(
            setOf(UNSYNCED_DATA),
            signOutLosses(edits, allSynced.copy(courseColours = false), hasImportedClassTables = false),
        )
    }

    @Test
    fun `imported class tables are always asked about, after the edits`() {
        assertEquals(setOf(IMPORTED_CLASS_TABLES), signOutLosses(LocalEdits(), allSynced, hasImportedClassTables = true))
        assertEquals(
            listOf(UNSYNCED_DATA, IMPORTED_CLASS_TABLES),
            signOutLosses(LocalEdits(assignmentMarks = true), syncOff, hasImportedClassTables = true).sortedBy { it.ordinal },
        )
    }

    // What the phone holds

    private val context: Application = RuntimeEnvironment.getApplication()
    private val prefs = AppPreferences(context).apply { cloudSyncEnabled = false }
    private val dataCache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), prefs))
    private val imported = ImportedClassTableStore({ ImportedClassTableStore.fileIn(context) }, { "B11300000" }, Dispatchers.Unconfined)
    private val check = SignOutLossCheck(dataCache, prefs, imported)

    private fun course(no: String) = Course(courseNo = no, courseName = "Course $no")

    @Test
    fun `fetched courses alone are not the user's own`() = runTest {
        dataCache.saveCourses(listOf(course("CS1001301")), "1141")

        assertEquals(emptySet<SignOutLoss>(), check.losses())
    }

    @Test
    fun `a course added by hand in an earlier term is the user's own`() = runTest {
        dataCache.saveCourses(listOf(course("CS1001301"), course("XX0000001").copy(isManual = true)), "1132")

        assertEquals(setOf(UNSYNCED_DATA), check.losses())
    }

    @Test
    fun `a picked colour and a marked assignment are the user's own`() = runTest {
        dataCache.saveCourses(listOf(course("CS1001301").copy(customColorHex = "#FF0000")), "1141")
        assertEquals(setOf(UNSYNCED_DATA), check.losses())

        dataCache.saveCourses(listOf(course("CS1001301")), "1141")
        dataCache.saveMarkedCompletedAssignments(setOf("a1"))
        assertEquals(setOf(UNSYNCED_DATA), check.losses())
    }

    @Test
    fun `an imported class table is named`() = runTest {
        val code = ClassTableShareCode(
            ClassTableShareCode.Format.TIGER_DUCK,
            "B11300001",
            null,
            listOf(ClassTableShareCode.Term("1141", listOf("CS1001301"))),
        )
        imported.add(code, "Ming", emptyMap())

        assertEquals(setOf(IMPORTED_CLASS_TABLES), check.losses())
    }
}
