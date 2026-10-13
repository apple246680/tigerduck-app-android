package org.ntust.app.tigerduck.ui.screen.settings

/**
 * Sends what Use Local decided for each assignment ([marksNow]), reading this
 * phone's marks again before a request whenever [marksVersion] says they were
 * written since the last read. A tap made while the requests go out is this
 * phone's newer mark and has sent its own PATCH; the status read before it
 * would undo it. An id the tap left with nothing to send is skipped.
 */
internal suspend fun sendKeptMarks(
    marksVersion: () -> Int,
    marksNow: suspend () -> Map<String, String>,
    send: suspend (assignmentId: Int, status: String) -> Unit,
) {
    // Taken before each read: a write after it shows as a newer version.
    var readAt = marksVersion()
    var marks = marksNow()
    for (id in marks.keys.toList()) {
        if (marksVersion() != readAt) {
            readAt = marksVersion()
            marks = marksNow()
        }
        val status = marks[id] ?: continue
        val intId = id.toIntOrNull() ?: continue
        runCatching { send(intId, status) }
    }
}
