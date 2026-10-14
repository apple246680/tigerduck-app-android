package org.ntust.app.tigerduck.data.preferences

import org.ntust.app.tigerduck.data.model.AppFeature

/**
 * One-shot: pins Library, left of More, on an existing user's stored bottom
 * bar that has room for it.
 *
 * The library used to sit behind an opt-in switch, so most bars never had it.
 * The switch is gone and the library is always on. A bar the user never
 * changed is not stored and already reads [AppFeature.defaultTabs], which has
 * Library; a full bar stays as it is, and so does the bar of a user who had
 * the library on, which pinned it, and took it off since. The switch's stored
 * value is deleted. Mirrors the Apple app's `LibraryTabAddMigration`.
 */
internal object LibraryTabAddMigration {

    /** Returns whether it stored a new bar. */
    fun runIfNeeded(prefs: AppPreferences): Boolean {
        if (prefs.libraryTabAddChecked) return false
        prefs.libraryTabAddChecked = true
        val hadLibraryOn = prefs.takeLegacyLibraryOptIn()
        if (hadLibraryOn || !prefs.hasStoredConfiguredTabs) return false
        val tabs = tabsAddingLibrary(prefs.configuredTabs) ?: return false
        prefs.configuredTabs = tabs
        return true
    }

    /** [tabs] with Library last, which puts it left of More, or null when the bar has it or is full. */
    fun tabsAddingLibrary(tabs: List<AppFeature>): List<AppFeature>? =
        if (AppFeature.LIBRARY in tabs || tabs.size >= AppFeature.MAX_CUSTOM_TABS) null
        else tabs + AppFeature.LIBRARY
}
