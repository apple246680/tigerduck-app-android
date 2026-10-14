package org.ntust.app.tigerduck.ui.screen.classtable.sharing

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The camera half of the class table import page, which each flavor supplies as
 * `FlavorQrScannerCamera`: play opens Google's code scanner, which runs in Play Services and needs
 * no camera permission; fdroid, which has no Play Services, shows its own CameraX preview read
 * with ZXing. Reading a code from a picture is the same on both, see `QrCodeImageReader`.
 */
interface QrScannerCamera {
    /**
     * Fills the page's camera area. A scanner that goes on scanning hands each code it sees to
     * [onScanned], which says whether the import took it; one that stops after one code hands it
     * to [onRead]. Without a camera the area stays empty, as the iOS page does; a scanner that
     * failed to open keeps a way to try again. The photo button below it works either way.
     */
    @Composable
    fun Area(onScanned: (String) -> Boolean, onRead: (String) -> Unit, modifier: Modifier)
}

/**
 * Lets a code through once while it stays in view. A camera reads a code on every frame that
 * shows it, and without this a code still in front of it would open its confirm step again the
 * moment it was cancelled. The iOS app's `RepeatedCodeFilter`.
 */
class RepeatedQrCodeFilter {
    private val lastSeen = mutableMapOf<String, Long>()

    /** Whether [text], read at [nowMs], is new: unseen, or out of view for longer than [GAP_MS]. */
    fun isNew(text: String, nowMs: Long): Boolean {
        val seen = lastSeen[text]
        lastSeen.entries.removeAll { nowMs - it.value > GAP_MS }
        lastSeen[text] = nowMs
        return seen == null || nowMs - seen > GAP_MS
    }

    companion object {
        /** Longer than the time between two frames that show the same code. */
        const val GAP_MS = 1_000L
    }
}

/**
 * A camera bound for as long as the import page is there. The camera provider answers some time
 * after the page asks, and the page can have left by then: binding the camera at that point
 * would keep it running over the class table, and start it again on every return to the app.
 * Called on the main thread only.
 */
class CameraSession {
    private var disposed = false
    private var unbind: (() -> Unit)? = null

    /** Binds with [bind], which returns how to let go, unless the page has left; says whether it bound. */
    fun start(bind: () -> (() -> Unit)): Boolean {
        if (disposed) return false
        unbind = bind()
        return true
    }

    /** The page left: lets go of what was bound, and binds nothing from now on. */
    fun dispose() {
        disposed = true
        unbind?.invoke()
        unbind = null
    }
}
