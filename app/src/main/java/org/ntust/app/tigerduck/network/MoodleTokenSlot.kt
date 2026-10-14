package org.ntust.app.tigerduck.network

import kotlin.reflect.KMutableProperty0

/**
 * Stores [token] in [slot] for a sign-in that [stillCurrent] says is still the account's, and
 * says whether it kept it. A sign-out can land between the check and the write, so it checks
 * again after and takes the token back out when the sign-in ended meanwhile: stored, the
 * departing student's token would answer for whoever signs in next. A token someone else stored
 * since stays.
 */
internal fun storeIfStillCurrent(token: String, slot: KMutableProperty0<String?>, stillCurrent: () -> Boolean): Boolean {
    if (!stillCurrent()) return false
    slot.set(token)
    if (stillCurrent()) return true
    if (slot.get() == token) slot.set(null)
    return false
}
