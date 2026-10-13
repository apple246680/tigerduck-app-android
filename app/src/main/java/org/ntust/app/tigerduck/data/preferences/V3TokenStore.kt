package org.ntust.app.tigerduck.data.preferences

import android.content.SharedPreferences
import android.util.Log

/**
 * The backend's v3 access token, refresh token and expiry, kept in
 * [CredentialManager]'s encrypted preferences.
 */
class V3TokenStore internal constructor(private val prefs: SharedPreferences) {

    val accessToken: String? get() = prefs.getString(KEY_ACCESS_TOKEN, null)
    val refreshToken: String? get() = prefs.getString(KEY_REFRESH_TOKEN, null)
    val expiresAt: Long get() = prefs.getLong(KEY_EXPIRES_AT, 0L)

    /**
     * Writes the three in one edit and commits it, so the pair is on disk
     * when this returns. The backend rotates the refresh token on every
     * refresh and revokes the device's sessions when the old one comes back
     * later; a pair still queued by `apply()` when the process dies would
     * bring the old one back on the next launch. A commit the disk refuses
     * still leaves the pair in memory, and this process keeps using it.
     */
    fun save(accessToken: String, refreshToken: String, expiresAt: Long) {
        val written = prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_EXPIRES_AT, expiresAt)
            .commit()
        if (!written) Log.w(TAG, "v3 token pair not written to disk")
    }

    /**
     * Removes the three, committed like [save]: a refresh token left on disk
     * after its refresh failed may have been rotated already, and the next
     * launch would send it again.
     */
    fun clear() {
        val written = prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .commit()
        if (!written) Log.w(TAG, "v3 token pair not removed from disk")
    }

    internal companion object {
        const val KEY_ACCESS_TOKEN = "v3_access_token"
        const val KEY_REFRESH_TOKEN = "v3_refresh_token"
        const val KEY_EXPIRES_AT = "v3_token_expires_at"
        private const val TAG = "V3TokenStore"
    }
}
