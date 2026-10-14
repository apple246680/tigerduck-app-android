package org.ntust.app.tigerduck.auth

import android.content.Context
import android.content.SharedPreferences
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.network.MoodleWebserviceError
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The school refused the stored password in a sign-in the user did not start. Each refusal counts
 * toward the account's lockout, which keeps the student out of every school system, so from then
 * on no automatic sign-in posts that password: not the SSO sign-ins the pages and the background
 * sync make, and not the Moodle token harvest. It lifts when a sign-in by hand stores the password
 * the school took, which is how a changed password gets in, and on sign-out. The iPhone app keeps the same latch
 * (`SSOLoginService.storedPasswordRejected`).
 *
 * One for the whole app, and kept across restarts: a process the background sync starts must not
 * try the password again either. It holds a flag and the school's message, never the password or
 * anything made from it.
 */
interface StoredPasswordLatch {
    /** Null while automatic sign-ins may post the stored password; otherwise the refusal. */
    val rejection: StateFlow<Rejection?>

    /** Whether a refusal holds for the stored account: never with none stored. */
    val isRejected: Boolean get() = rejection.value != null

    /**
     * Moved by [clear]. An automatic sign-in reads it before it posts the password and hands it to
     * [markRejected], so a refusal that arrives after the user signed in again, or signed out,
     * latches nothing.
     */
    val generation: Int

    /**
     * Latches after the school refused [password] for [studentId] in a sign-in the user did not
     * start, which read [generation] before it posted them. [reason] is the school's message, when
     * the page carried one. Nothing latches once [clear] has run since, or when the stored account
     * is no longer this one: a sign-in with a new password can finish while one with the old
     * password is still under way. While a refusal holds, it stays as it is.
     */
    fun markRejected(generation: Int, studentId: String, password: String, reason: String?)

    /** When a sign-in the user made stores the password the school took, and on sign-out. */
    fun clear()

    /**
     * Held by every automatic post of the stored password, the SSO sign-ins' and the Moodle token
     * renewal's alike, from its last look at the latch right before the post until it has read
     * the school's answer and latched any refusal ([postAutomatically]): a refusal one of them
     * gets lands before the next one looks, never while it is already posting. A sign-in the user
     * makes never takes it.
     */
    val automaticPosts: Mutex

    /**
     * One refusal. Not a data class: two refusals with the same message are still two, and a
     * banner closed for one shows again for the next.
     */
    class Rejection(val reason: String?)
}

/**
 * Runs [post], an automatic sign-in's post of the stored password through to reading the school's
 * answer, holding [StoredPasswordLatch.automaticPosts], and not at all while a refusal holds:
 * [refused] makes the error then. The look at the latch that counts is this one, made holding the
 * lock, so no other automatic post's refusal can land between it and this post. [post] latches a
 * refusal it gets before it returns.
 */
internal suspend fun <T> StoredPasswordLatch.postAutomatically(
    refused: (StoredPasswordLatch.Rejection) -> Exception,
    post: suspend () -> T,
): T = automaticPosts.withLock {
    rejection.value?.let { throw refused(it) }
    post()
}

/**
 * How a Moodle token harvest posts the password to NTUST's SSO: [post] runs `send`, which posts it
 * and reads SSO's answer, throwing its refusal as [MoodleWebserviceError.SsoLoginRejected].
 */
internal interface PasswordPosting {
    suspend fun <T> post(send: suspend () -> T): T

    /** For credentials the user just typed: posted as they are, whatever the latch holds. */
    object Typed : PasswordPosting {
        override suspend fun <T> post(send: suspend () -> T): T = send()
    }
}

/**
 * Runs [harvest], a Moodle token harvest the user did not start, which posts the stored
 * [password] to NTUST's SSO with the [PasswordPosting] it is handed: not at all while the latch is
 * set, and latching when SSO refuses the password it posted. That posting takes
 * [StoredPasswordLatch.automaticPosts] and checks the latch again right before the password goes
 * out ([postAutomatically]), so a refusal another sign-in latched meanwhile stops it too. The
 * harvest a sign-in the user makes posts with [PasswordPosting.Typed].
 */
internal suspend fun <T> StoredPasswordLatch.guardAutomaticHarvest(
    studentId: String,
    password: String,
    harvest: suspend (posting: PasswordPosting) -> T,
): T {
    rejection.value?.let { throw MoodleWebserviceError.SsoLoginRejected(it.reason) }
    val before = generation
    val latch = this
    return harvest(object : PasswordPosting {
        override suspend fun <R> post(send: suspend () -> R): R =
            latch.postAutomatically({ MoodleWebserviceError.SsoLoginRejected(it.reason) }) {
                try {
                    send()
                } catch (e: MoodleWebserviceError.SsoLoginRejected) {
                    latch.markRejected(before, studentId, password, e.reason)
                    throw e
                }
            }
    })
}

/**
 * [StoredPasswordLatch] in its own preferences file, as a flag and a nullable message: a missing
 * key reads as not latched, so the file needs no migration.
 */
@Singleton
class PersistedStoredPasswordLatch internal constructor(
    private val prefs: SharedPreferences,
    /** The stored student ID and password, or null with none stored. */
    private val storedAccount: () -> Pair<String, String>?,
) : StoredPasswordLatch {

    @Inject
    constructor(@ApplicationContext context: Context, credentials: CredentialManager) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        {
            val studentId = credentials.ntustStudentId
            val password = credentials.ntustPassword
            if (studentId == null || password == null) null else studentId to password
        },
    )

    private val lock = Any()
    private var currentGeneration = 0

    override val automaticPosts = Mutex()

    private val _rejection = MutableStateFlow(
        if (prefs.getBoolean(KEY_REJECTED, false)) {
            StoredPasswordLatch.Rejection(prefs.getString(KEY_REASON, null))
        } else {
            null
        },
    )
    override val rejection: StateFlow<StoredPasswordLatch.Rejection?> = _rejection.asStateFlow()

    // The refusal was of the stored password; with no account stored, there is none to refuse,
    // whatever a sign-out that never ran its clear left behind.
    override val isRejected: Boolean get() = _rejection.value != null && storedAccount() != null

    override val generation: Int get() = synchronized(lock) { currentGeneration }

    override fun markRejected(
        generation: Int,
        studentId: String,
        password: String,
        reason: String?,
    ) {
        synchronized(lock) {
            // One already holds: a new one would show a banner the user closed again.
            if (_rejection.value != null) return
            if (generation != currentGeneration) return
            val (storedId, storedPassword) = storedAccount() ?: return
            if (normalized(storedId) != normalized(studentId) || storedPassword != password) return
            val message = reason?.trim()?.take(MAX_REASON_LENGTH)?.takeIf { it.isNotEmpty() }
            // Written through before anything reads it: a process the system ends right
            // after must not start the next one unlatched.
            prefs.edit().apply {
                putBoolean(KEY_REJECTED, true)
                if (message == null) remove(KEY_REASON) else putString(KEY_REASON, message)
            }.commit()
            _rejection.value = StoredPasswordLatch.Rejection(message)
        }
    }

    override fun clear() {
        synchronized(lock) {
            currentGeneration++
            if (_rejection.value == null && !prefs.contains(KEY_REJECTED)) return
            prefs.edit().remove(KEY_REJECTED).remove(KEY_REASON).apply()
            _rejection.value = null
        }
    }

    private fun normalized(studentId: String) = studentId.trim().uppercase()

    private companion object {
        const val PREFS_NAME = "tigerduck_auth"
        const val KEY_REJECTED = "stored_password_rejected"
        const val KEY_REASON = "stored_password_rejected_reason"

        // The school's message is a line or two; anything longer is not one.
        const val MAX_REASON_LENGTH = 200
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class StoredPasswordLatchModule {
    @Binds
    abstract fun bindStoredPasswordLatch(latch: PersistedStoredPasswordLatch): StoredPasswordLatch
}
