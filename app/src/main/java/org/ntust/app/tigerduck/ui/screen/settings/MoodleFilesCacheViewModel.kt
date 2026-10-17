package org.ntust.app.tigerduck.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.data.moodle.MoodleFileDownloads
import javax.inject.Inject

/**
 * Settings' "Moodle materials": what the downloaded Moodle files take on the device, measured each
 * time the screen shows, and clearing them all with no confirmation, since each downloads again
 * on a tap. The cached lists stay, so every Moodle screen still fills. As iOS's
 * `MoodleFilesCacheSection`.
 */
@HiltViewModel
class MoodleFilesCacheViewModel @Inject constructor(
    private val downloads: MoodleFileDownloads,
) : ViewModel() {
    private val _bytes = MutableStateFlow<Long?>(null)

    /** Null until the first measure ends, so the row says "…" rather than a size not measured. */
    val bytes: StateFlow<Long?> = _bytes.asStateFlow()

    fun measure() {
        viewModelScope.launch { _bytes.value = downloads.measure() }
    }

    fun clear() {
        viewModelScope.launch { _bytes.value = downloads.clear() }
    }
}
