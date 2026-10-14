package org.ntust.app.tigerduck.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Library pages on screen. The flip-to-library offer waits while one is
 * up, since the prompt would cover the code someone just signed in to show,
 * and shows once the page is left. Each page counts once however often it
 * appears, and a tab switch can show one page before it hides another.
 * Mirrors the Apple app's `LibraryPagePresence`.
 */
@Singleton
class LibraryPagePresence @Inject constructor() {
    private val pages = mutableSetOf<Any>()
    private val _shown = MutableStateFlow(false)

    /** Whether a Library page is on screen. */
    val shown: StateFlow<Boolean> = _shown.asStateFlow()

    fun appear(page: Any) = synchronized(pages) {
        pages += page
        _shown.value = true
    }

    /**
     * [page] left the screen. One torn down only to be built again
     * ([recreating]), as at a rotation, stays: it shows again once rebuilt,
     * and a held prompt would show for the frame in between.
     */
    fun disappear(page: Any, recreating: Boolean = false) = synchronized(pages) {
        if (recreating) return@synchronized
        pages -= page
        _shown.value = pages.isNotEmpty()
    }
}
