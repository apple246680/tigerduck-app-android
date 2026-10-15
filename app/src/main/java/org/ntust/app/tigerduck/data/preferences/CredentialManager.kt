@file:Suppress("DEPRECATION")

package org.ntust.app.tigerduck.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import org.ntust.app.tigerduck.mail.store.MailCredentialStore
import org.ntust.app.tigerduck.shared.LibraryAccountGate
import org.ntust.app.tigerduck.shared.LibraryCredentialStore
import org.ntust.app.tigerduck.shared.sameStudentId
import org.ntust.app.tigerduck.util.logSummary
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CredentialManager private constructor(
    private val prefs: SharedPreferences,
    encrypted: Boolean,
    recreated: Boolean,
) : LibraryCredentialStore, MailCredentialStore {

    @Inject
    constructor(@ApplicationContext context: Context) : this(openEncrypted(context))

    private constructor(opened: Opened) : this(opened.prefs, opened.encrypted, opened.recreated)

    /** [prefs] as the store, unencrypted: for tests, which cannot reach the Android keystore. */
    @VisibleForTesting
    internal constructor(prefs: SharedPreferences) : this(prefs, encrypted = false, recreated = false)

    val isEncrypted: Boolean = encrypted

    /**
     * True when [openEncrypted] had to delete the backing file and
     * rebuild an empty credential store (keystore corruption, restore-to-new-
     * device, etc). Callers can use this signal to prompt the user to re-login
     * rather than silently presenting an unauthenticated app.
     */
    val wasRecreatedDueToCorruption: Boolean = recreated

    var ntustStudentId: String?
        get() = prefs.getString("ntust_student_id", null)
        set(value) = if (value != null)
            prefs.edit().putString("ntust_student_id", value).apply()
        else
            prefs.edit().remove("ntust_student_id").apply()

    var ntustPassword: String?
        get() = prefs.getString("ntust_password", null)
        set(value) = if (value != null)
            prefs.edit().putString("ntust_password", value).apply()
        else
            prefs.edit().remove("ntust_password").apply()

    override val libraryUsername: String?
        get() = prefs.getString("library_username", null)

    override val libraryPassword: String?
        get() = prefs.getString("library_password", null)

    override val libraryToken: String?
        get() = prefs.getString("library_token", null)

    override val libraryTokenExpiry: Long
        get() = prefs.getLong("library_token_expiry", 0L)

    override val isLibraryTokenValid: Boolean
        get() = libraryToken != null && System.currentTimeMillis() < libraryTokenExpiry

    private val libraryAccount = LibraryAccountGate()

    override val libraryAccountGeneration: Int get() = libraryAccount.current

    override fun saveLibrarySignIn(
        generation: Int,
        username: String,
        password: String,
        token: String?,
        tokenExpiry: Long,
        byUser: Boolean,
        stillCurrent: () -> Boolean,
    ): Boolean = libraryAccount.saveIfCurrent(generation, byUser, { libraryUsername }, username, stillCurrent) {
        prefs.edit()
            .putString("library_username", username)
            .putString("library_password", password)
            .putString("library_token", token)
            .putLong("library_token_expiry", tokenExpiry)
            // Credentials that work, so the app may sign in with them again.
            .remove(KEY_LIBRARY_PASSWORD_REFUSED)
            .apply()
    }

    override val libraryPasswordRefused: Boolean
        get() = prefs.getBoolean(KEY_LIBRARY_PASSWORD_REFUSED, false)

    override fun refuseLibraryPassword(generation: Int, password: String): Boolean =
        libraryAccount.whileCurrent(generation) {
            if (libraryPassword != password) return@whileCurrent false
            prefs.edit().putBoolean(KEY_LIBRARY_PASSWORD_REFUSED, true).apply()
            true
        } ?: false

    /** Long-lived Moodle Mobile wstoken, obtained via the OIDC launch flow. */
    var moodleToken: String?
        get() = prefs.getString("moodle_token", null)
        set(value) = if (value != null)
            prefs.edit().putString("moodle_token", value).apply()
        else
            prefs.edit().remove("moodle_token").apply()

    /**
     * Stores the Moodle [token] only while [studentId] is still the stored account. Under the
     * same lock as [clearNtustCredentials] and [clearAll], so a sign-out cannot land between the
     * check and the store and leave the departing account's token for the next one.
     */
    @Synchronized
    fun storeMoodleTokenIfSignedIn(studentId: String, token: String): Boolean {
        if (ntustStudentId != studentId) return false
        moodleToken = token
        return true
    }

    // ── v3 JWT tokens ────────────────────────────────────────────────────────

    val v3Tokens = V3TokenStore(prefs)
    val pendingUnregister = PendingUnregisterPrefs(prefs)

    @Synchronized
    fun clearNtustCredentials() {
        prefs.edit()
            .remove("ntust_student_id")
            .remove("ntust_password")
            .remove("moodle_token")
            // Not the backend session, nor its refresh token's send count:
            // AuthTokenManager.logout() drops them under its own lock, and hands
            // the session to the sign-out's unregister as it does.
            .apply()
    }

    fun clearLibraryCredentials() {
        // A library sign-in still running for this account then stores nothing.
        libraryAccount.signOut {
            prefs.edit()
                .remove("library_username")
                .remove("library_password")
                .remove("library_token")
                .remove("library_token_expiry")
                .remove(KEY_LIBRARY_PASSWORD_REFUSED)
                .apply()
        }
    }

    // Moved by each school sign-out, under the library account's lock.
    private var schoolAccount = 0

    /**
     * Moved by each school sign-out. The school sign-in reads it before its
     * SSO step, and its own library step stores nothing once it has moved
     * (see [isSchoolAccount]). Only that step: a sign-in with another
     * student's stored library credentials, which the sign-out keeps, still
     * stores its token or its refusal, as on the iPhone.
     */
    val schoolAccountGeneration: Int get() = libraryAccount.atomically { schoolAccount }

    /**
     * Whether no school sign-out has come since [read] was read from
     * [schoolAccountGeneration]. Asked as the library step's answer is
     * stored, under the same lock as the sign-out.
     */
    fun isSchoolAccount(read: Int): Boolean = libraryAccount.atomically { schoolAccount == read }

    /**
     * The library sign-out a school sign-out makes: the library account goes
     * when it is [ifSchoolAccount]'s, the departing student ID, compared as
     * [sameStudentId] does; another student's stays. Either way the school
     * sign-in's own library step, if still out, stores nothing. Atomic with
     * the library's saves, so that step cannot store the departing account
     * after this looked. Returns whether the library account went.
     */
    fun clearLibraryCredentials(ifSchoolAccount: String?): Boolean = libraryAccount.atomically {
        schoolAccount++
        val same = ifSchoolAccount != null && sameStudentId(libraryUsername, ifSchoolAccount)
        if (same) clearLibraryCredentials()
        same
    }

    /**
     * A school sign-out's credentials: the school account goes, and the library
     * account with it when it is the same student's, by the student ID read
     * before the school's is wiped; see [clearLibraryCredentials]. Returns
     * whether the library account went.
     */
    fun signOutSchoolAccount(): Boolean {
        val departing = ntustStudentId
        clearNtustCredentials()
        return clearLibraryCredentials(ifSchoolAccount = departing)
    }

    // ── School mail — its own account, never shared with the NTUST or library one ──

    override var mailStudentId: String?
        get() = prefs.getString("mail_student_id", null)
        set(value) = if (value != null)
            prefs.edit().putString("mail_student_id", value).apply()
        else
            prefs.edit().remove("mail_student_id").apply()

    override var mailPassword: String?
        get() = prefs.getString("mail_password", null)
        set(value) = if (value != null)
            prefs.edit().putString("mail_password", value).apply()
        else
            prefs.edit().remove("mail_password").apply()

    override fun clearMailCredentials() {
        prefs.edit()
            .remove("mail_student_id")
            .remove("mail_password")
            .apply()
    }

    /** Wipe every credential key. Used by the full-reset flow. */
    @Synchronized
    fun clearAll() {
        // All but the device unregister a sign-out still owes; see PendingUnregisterPrefs.
        libraryAccount.signOut { clearAllButOwedUnregister(prefs) }
    }

    private class Opened(val prefs: SharedPreferences, val encrypted: Boolean, val recreated: Boolean)

    companion object {
        private const val PREFS_NAME = "tigerduck_credentials"
        private const val KEY_LIBRARY_PASSWORD_REFUSED = "library_password_refused"

        private fun openEncrypted(context: Context): Opened {
            fun attempt(): SharedPreferences {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                return EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }
            return try {
                Opened(attempt(), encrypted = true, recreated = false)
            } catch (e: Exception) {
                // A corrupted prefs file (e.g. after a system restore onto a
                // device whose keystore doesn't hold the original master key)
                // makes `create` throw permanently. Wipe and retry once so the
                // user only loses their stored credentials, not the whole app.
                android.util.Log.w(
                    "CredentialManager",
                    "EncryptedSharedPreferences unusable; resetting: ${e.logSummary()}"
                )
                runCatching { context.deleteSharedPreferences(PREFS_NAME) }
                try {
                    Opened(attempt(), encrypted = true, recreated = true)
                } catch (retry: Exception) {
                    android.util.Log.e(
                        "CredentialManager",
                        "EncryptedSharedPreferences retry failed: ${retry.logSummary()}"
                    )
                    throw SecurityException("Cannot create encrypted credential storage", retry)
                }
            }
        }
    }
}
