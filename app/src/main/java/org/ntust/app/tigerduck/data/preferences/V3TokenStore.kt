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
     * The backend that issued the pair and the student it is for, recorded at
     * sign-in for the sign-out's unregister; null for a pair stored before they
     * were. A refresh keeps them.
     */
    val issuedBy: String? get() = prefs.getString(KEY_ISSUED_BY, null)
    val studentId: String? get() = prefs.getString(KEY_STUDENT_ID, null)

    /**
     * Writes the three in one edit and commits it, so the pair is on disk
     * when this returns. The backend rotates the refresh token on every
     * refresh and revokes the device's sessions when the old one comes back
     * later; a pair still queued by `apply()` when the process dies would
     * bring the old one back on the next launch. A commit the disk refuses
     * still leaves the pair in memory, and this process keeps using it.
     */
    fun save(
        accessToken: String,
        refreshToken: String,
        expiresAt: Long,
        issuedBy: String? = null,
        studentId: String? = null,
    ) {
        val editor = prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_EXPIRES_AT, expiresAt)
        if (issuedBy != null) editor.putString(KEY_ISSUED_BY, issuedBy)
        if (studentId != null) editor.putString(KEY_STUDENT_ID, studentId)
        val written = editor.commit()
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
            .remove(KEY_ISSUED_BY)
            .remove(KEY_STUDENT_ID)
            .commit()
        if (!written) Log.w(TAG, "v3 token pair not removed from disk")
    }

    internal companion object {
        const val KEY_ACCESS_TOKEN = "v3_access_token"
        const val KEY_REFRESH_TOKEN = "v3_refresh_token"
        const val KEY_EXPIRES_AT = "v3_token_expires_at"
        const val KEY_ISSUED_BY = "v3_issued_by"
        const val KEY_STUDENT_ID = "v3_student_id"
        private const val TAG = "V3TokenStore"
    }
}
