package org.ntust.app.tigerduck.data.preferences

import android.content.SharedPreferences
import android.util.Log
import org.ntust.app.tigerduck.auth.DepartingSession
import org.ntust.app.tigerduck.push.PendingUnregister
import org.ntust.app.tigerduck.push.PendingUnregisterStore

/**
 * The device unregister a sign-out still owes, in the encrypted credential store beside the
 * session it was taken from. The sign-out's own clearing leaves it: it is what outlasts the
 * sign-out.
 */
class PendingUnregisterPrefs internal constructor(private val prefs: SharedPreferences) : PendingUnregisterStore {

    override fun load(): PendingUnregister? {
        val deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: return null
        val access = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return null
        val refresh = prefs.getString(KEY_REFRESH_TOKEN, null) ?: return null
        return PendingUnregister(deviceId, DepartingSession(access, refresh, prefs.getLong(KEY_EXPIRES_AT, 0L)))
    }

    /**
     * Committed, as [V3TokenStore.save] is: a rotated refresh token still queued by `apply()`
     * when the process dies would bring the replaced one back on the next try.
     */
    override fun save(pending: PendingUnregister) {
        val written = prefs.edit()
            .putString(KEY_DEVICE_ID, pending.deviceId)
            .putString(KEY_ACCESS_TOKEN, pending.session.accessToken)
            .putString(KEY_REFRESH_TOKEN, pending.session.refreshToken)
            .putLong(KEY_EXPIRES_AT, pending.session.accessExpiresAt)
            .commit()
        if (!written) Log.w(TAG, "owed unregister not written to disk")
    }

    override fun clear() {
        val written = prefs.edit()
            .remove(KEY_DEVICE_ID)
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .commit()
        if (!written) Log.w(TAG, "owed unregister not removed from disk")
    }

    internal companion object {
        const val KEY_DEVICE_ID = "departing_device_id"
        const val KEY_ACCESS_TOKEN = "departing_access_token"
        const val KEY_REFRESH_TOKEN = "departing_refresh_token"
        const val KEY_EXPIRES_AT = "departing_expires_at"
        private const val TAG = "PendingUnregisterPrefs"
    }
}
