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

/**
 * Edits the server has not confirmed, which a sign-out deletes even with their kind synced: a
 * course added by hand that has not been uploaded, an ignore not yet confirmed, a done or ignored
 * mark still waiting to be sent (UnsentMarks), and edits whose upload the re-enable check holds
 * back (the sign-out drops the hold).
 */
data class Unconfirmed(
    val courses: Boolean = false,
    val courseNames: Boolean = false,
    val courseColours: Boolean = false,
    val assignments: Boolean = false,
)

/** What TigerSync keeps for the account; on fdroid, nothing. */
data class SyncedKinds(
    val enabled: Boolean,
    val courses: Boolean,
    val courseNames: Boolean,
    val courseColours: Boolean,
    val assignments: Boolean,
)

fun signOutLosses(
    edits: LocalEdits,
    synced: SyncedKinds,
    hasImportedClassTables: Boolean,
    unconfirmed: Unconfirmed = Unconfirmed(),
): Set<SignOutLoss> {
    fun kept(kind: Boolean) = synced.enabled && kind
    val unsynced = (edits.courses && !kept(synced.courses)) ||
        (edits.courseNames && !kept(synced.courseNames)) ||
        (edits.courseColours && !kept(synced.courseColours)) ||
        (edits.assignmentMarks && !kept(synced.assignments)) ||
        unconfirmed.courses || unconfirmed.courseNames || unconfirmed.courseColours || unconfirmed.assignments
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
        val terms = dataCache.loadEveryTermCourses()
        val courses = terms.values.flatten()
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
        // Held back for the re-enable check, by its category names (REENABLE_CATEGORIES).
        val held = prefs.pendingConflictCategories
        val serverKnown = dataCache.loadServerKnownNos()
        val unconfirmed = Unconfirmed(
            courses = (edits.courses && "courses" in held) || terms.any { (semester, termCourses) ->
                termCourses.any { it.isManual && it.courseNo !in serverKnown[semester].orEmpty() }
            },
            courseNames = edits.courseNames && "course_names" in held,
            courseColours = edits.courseColours && "course_colors" in held,
            assignments = (edits.assignmentMarks && "assignments" in held) ||
                dataCache.loadPendingCourseIgnores().isNotEmpty() ||
                // A mark tapped offline waits in unsent_marks.json for the next sync.
                dataCache.loadUnsentMarks().isNotEmpty(),
        )
        return signOutLosses(edits, synced, importedClassTables.load().hasImportedClassTables, unconfirmed)
    }
}
