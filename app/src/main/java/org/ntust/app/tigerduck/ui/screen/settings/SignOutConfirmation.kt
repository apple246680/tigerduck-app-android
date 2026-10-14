package org.ntust.app.tigerduck.ui.screen.settings

import androidx.annotation.StringRes
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.cache.DataCache
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableStore
import javax.inject.Inject

/**
 * What signing out of the school account deletes that exists nowhere else, each named by a line
 * of the confirmation the sign-out asks first with. With none, it signs out at once.
 */
enum class SignOutLoss(@param:StringRes val message: Int) {
    /** The user's own edits that TigerSync does not keep: it is off, or that kind's switch is. */
    UNSYNCED_DATA(R.string.settings_sign_out_unsynced_data),

    /** The class tables imported from share codes, which only this phone has. Last: "…too". */
    IMPORTED_CLASS_TABLES(R.string.settings_sign_out_imported_tables),
}

/** The user's own edits on this phone, by the TigerSync switch that would keep each. */
data class LocalEdits(
    /** Courses added by hand, or hidden from the class table. */
    val courses: Boolean = false,
    val courseNames: Boolean = false,
    val courseColours: Boolean = false,
    /** Assignments ignored or marked done, and courses whose assignments are ignored. */
    val assignmentMarks: Boolean = false,
)

/** What TigerSync keeps for the account; on fdroid, nothing. */
data class SyncedKinds(
    val enabled: Boolean,
    val courses: Boolean,
    val courseNames: Boolean,
    val courseColours: Boolean,
    val assignments: Boolean,
)

fun signOutLosses(edits: LocalEdits, synced: SyncedKinds, hasImportedClassTables: Boolean): Set<SignOutLoss> {
    fun kept(kind: Boolean) = synced.enabled && kind
    val unsynced = (edits.courses && !kept(synced.courses)) ||
        (edits.courseNames && !kept(synced.courseNames)) ||
        (edits.courseColours && !kept(synced.courseColours)) ||
        (edits.assignmentMarks && !kept(synced.assignments))
    return buildSet {
        if (unsynced) add(SignOutLoss.UNSYNCED_DATA)
        if (hasImportedClassTables) add(SignOutLoss.IMPORTED_CLASS_TABLES)
    }
}

/**
 * Reads what this phone holds for [signOutLosses]. Off the main thread: it reads every term's
 * cached courses.
 */
class SignOutLossCheck @Inject constructor(
    private val dataCache: DataCache,
    private val prefs: AppPreferences,
    private val importedClassTables: ImportedClassTableStore,
) {
    suspend fun losses(): Set<SignOutLoss> {
        val courses = dataCache.loadEveryTermCourses()
        val edits = LocalEdits(
            courses = courses.any { it.isManual } || dataCache.loadDeletedCourseNos().isNotEmpty(),
            courseNames = courses.any { it.customCourseName != null } ||
                dataCache.loadCourseCustomNames().values.any { it.isNotEmpty() },
            courseColours = courses.any { it.customColorHex != null },
            assignmentMarks = dataCache.loadIgnoredAssignments().isNotEmpty() ||
                dataCache.loadMarkedCompletedAssignments().isNotEmpty() ||
                dataCache.loadIgnoredCourses().isNotEmpty(),
        )
        val synced = SyncedKinds(
            enabled = prefs.cloudSyncEnabled,
            courses = prefs.syncCourses,
            courseNames = prefs.syncCourseNames,
            courseColours = prefs.syncCourseColors,
            assignments = prefs.syncAssignments,
        )
        return signOutLosses(edits, synced, importedClassTables.load().hasImportedClassTables)
    }
}
