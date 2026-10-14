package org.ntust.app.tigerduck.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.ui.theme.VisualPreset

/**
 * AppState's copy of the interface style picked in Settings > Display, held
 * as Compose state over its preference. The root theme reads it, so a pick
 * restyles every screen at once. Its own class because [AppState] needs a
 * full Hilt graph, and this needs only the store.
 */
internal class VisualPresetSetting(private val prefs: AppPreferences) {
    private var state by mutableStateOf(prefs.visualPreset)

    var value: VisualPreset
        get() = state
        set(value) {
            if (state == value) return
            state = value
            prefs.visualPreset = value
        }

    /** Reads the store again, after a full reset wiped it. */
    fun reload() {
        state = prefs.visualPreset
    }
}
