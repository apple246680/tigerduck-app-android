package org.ntust.app.tigerduck.ui.screen.settings

import org.ntust.app.tigerduck.ui.screen.home.AssignmentOverrideReconciler.STATUS_NONE

/**
 * Sends what Use Local decided for each assignment ([marksNow]), then reads
 * this phone's marks again and sends what changed meanwhile, until nothing
 * has. A tap made while the requests go out is this phone's newer mark, and a
 * status read before it but sent after it would undo it; an id that has left
 * [marksNow] since it was sent goes out as unmarked.
 */
internal suspend fun sendKeptMarks(
    marksNow: suspend () -> Map<String, String>,
    send: suspend (assignmentId: Int, status: String) -> Unit,
) {
    var sent = emptyMap<String, String>()
    while (true) {
        val marks = marksNow()
        val current = marks + (sent.keys - marks.keys).associateWith { STATUS_NONE }
        val changed = current.filter { (id, status) -> sent[id] != status }
        if (changed.isEmpty()) return
        for ((id, status) in changed) {
            val intId = id.toIntOrNull() ?: continue
            runCatching { send(intId, status) }
        }
        sent = sent + changed
    }
}
