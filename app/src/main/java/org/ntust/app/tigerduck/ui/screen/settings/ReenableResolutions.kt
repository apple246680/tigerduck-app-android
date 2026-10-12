package org.ntust.app.tigerduck.ui.screen.settings

/**
 * The re-enable conflict answers still being applied in this process, which
 * every Settings screen's view model shares. A check while one runs would
 * compare a half-sent state, and the answer finishing would end the hold on
 * assignment marks that check needs, so checks wait for the last to finish.
 */
internal class ReenableResolutions {

    private var running = 0
    private var waitingCheck: (() -> Unit)? = null

    @Synchronized
    fun start() {
        running++
    }

    /**
     * Whether [check] has to wait for the answers being applied. The last
     * check to wait runs when they have all finished, from the screen that
     * asked for it.
     */
    @Synchronized
    fun defers(check: () -> Unit): Boolean {
        if (running == 0) return false
        waitingCheck = check
        return true
    }

    /**
     * Called as each answer finishes. True when it was the last one and no
     * check waited, so the hold can go; a check that waited runs instead and
     * settles the hold itself.
     */
    fun finish(): Boolean {
        val check = synchronized(this) {
            running--
            if (running > 0) return false
            waitingCheck.also { waitingCheck = null }
        } ?: return true
        check()
        return false
    }
}
