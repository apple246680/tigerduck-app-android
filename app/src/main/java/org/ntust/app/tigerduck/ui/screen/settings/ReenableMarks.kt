package org.ntust.app.tigerduck.ui.screen.settings

import android.util.Log
import org.ntust.app.tigerduck.data.preferences.AppPreferences

private const val TAG = "SyncReenable"

/** Every category the re-enable check compares. */
internal val REENABLE_CATEGORIES = setOf("courses", "course_colors", "course_names", "assignments")

/**
 * The re-enable check's categories whose sync switch is on. `HomeBackendSync.pull`
 * reads them once for its course steps and, when it succeeds, adds them to
 * [AppPreferences.syncedCategories].
 */
internal fun categoriesSwitchedOn(prefs: AppPreferences): Set<String> = buildSet {
    if (prefs.syncCourses) add("courses")
    if (prefs.syncCourseColors) add("course_colors")
    if (prefs.syncCourseNames) add("course_names")
    if (prefs.syncAssignments) add("assignments")
}

/**
 * Marks [category], switched back on, for the re-enable check, which compares
 * this phone's copy of the account's data with the server's. Before a full
 * sync has taken the category's data for the account here, as after a sign-in,
 * there is no copy: Use Local would send the emptied caches over the server's,
 * so the category is left to its first sync, which takes the server's data.
 * Signed out, the check has no session, and the mark would wait for whoever
 * signs in next.
 */
internal fun markForReenableCheck(prefs: AppPreferences, category: String, signedIn: Boolean) {
    val synced = category in prefs.syncedCategories
    if (!signedIn || !synced) {
        Log.i(TAG, "[reenable] not marked: category=$category signedIn=$signedIn synced=$synced")
        return
    }
    prefs.pendingConflictCategories = prefs.pendingConflictCategories + category
    Log.i(TAG, "[reenable] marked category=$category pending=${prefs.pendingConflictCategories}")
}
