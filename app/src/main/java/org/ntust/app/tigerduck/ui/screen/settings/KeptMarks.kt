package org.ntust.app.tigerduck.ui.screen.settings

/**
 * Sends what Use Local decided for each assignment, taking this phone's
 * marks again ([marksNow]) before each request. A tap made while they go out
 * is this phone's newer mark and has sent its own PATCH; the status read
 * before it would undo it. An id the tap left with nothing to send is skipped.
 */
internal suspend fun sendKeptMarks(
    marksNow: suspend () -> Map<String, String>,
    send: suspend (assignmentId: Int, status: String) -> Unit,
) {
    for (id in marksNow().keys) {
        val intId = id.toIntOrNull() ?: continue
        val status = marksNow()[id] ?: continue
        runCatching { send(intId, status) }
    }
}
