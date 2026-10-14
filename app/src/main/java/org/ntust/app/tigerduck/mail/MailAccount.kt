package org.ntust.app.tigerduck.mail

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.mail.imap.MailFolders
import org.ntust.app.tigerduck.mail.imap.MailSessionFactory
import org.ntust.app.tigerduck.mail.imap.SpecialFolder
import org.ntust.app.tigerduck.mail.notify.MailNotifier
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.mail.store.MailCredentialStore
import org.ntust.app.tigerduck.mail.store.MailStateStore
import org.ntust.app.tigerduck.mail.sync.MailBackgroundScheduler
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The school mail account: its own sign-in, separate from the NTUST and
 * library ones (spec §7). Credentials are saved only after the server
 * accepted them.
 */
@Singleton
class MailAccount @Inject constructor(
    private val credentials: MailCredentialStore,
    private val state: MailStateStore,
    private val sessions: MailSessionFactory,
    private val cache: MailCache,
    private val demo: MailDemoGate,
    private val site: MailSite,
    private val scheduler: MailBackgroundScheduler,
    private val notifier: MailNotifier,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val _signedIn = MutableStateFlow(credentialsOrNull() != null)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    /**
     * One event per [signOut], for cleanup that must run on every one of
     * them. [signedIn] can't carry that: a StateFlow hands a collector only
     * the latest value, so a sign-in and a sign-out that both land before it
     * next runs read as no change at all. Buffered so [signOut] never
     * suspends; a subscriber that falls behind still gets one, which is all
     * cleanup needs.
     */
    private val _signOuts = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val signOuts: SharedFlow<Unit> = _signOuts.asSharedFlow()

    private val _authFailed = MutableStateFlow(state.authFailed)
    val authFailed: StateFlow<Boolean> = _authFailed.asStateFlow()

    val studentId: String? get() = credentials.mailStudentId

    private val generation = AtomicInteger()

    // Held while a sign-in or sign-out changes the account and its generation, and while
    // [snapshot] reads them, so a reader never pairs one account with the other's generation.
    private val accountLock = Any()

    /**
     * Moved by every sign-in and sign-out. Work that reads the account through [snapshot] and then
     * runs on compares this with the snapshot's: once it has moved, the work holds an account
     * that left, even when the same student has signed in again since.
     */
    val accountGeneration: Int get() = generation.get()

    /** The signed-in account and its [accountGeneration], read together; null while signed out. */
    class Snapshot(val generation: Int, val credentials: MailCredentials)

    fun snapshot(): Snapshot? = synchronized(accountLock) {
        credentialsOrNull()?.let { Snapshot(generation.get(), it) }
    }

    /**
     * The signed-in student's own address, without decrypting the password the way
     * [credentialsOrNull] does. Still a read of the encrypted store, so not for the main thread.
     */
    val ownAddress: String? get() = credentials.mailStudentId?.takeIf { it.isNotBlank() }?.let { MailCredentials.addressOf(it, site.domain()) }

    /** True for the mail demo mailbox itself, or whenever the app-wide demo is active -- either way, nothing may open a socket. */
    val isDemo: Boolean get() = state.demoMailbox || demo.appDemoActive

    fun credentialsOrNull(): MailCredentials? {
        val id = credentials.mailStudentId
        val password = credentials.mailPassword
        return if (id.isNullOrBlank() || password.isNullOrEmpty()) null else MailCredentials(id, password, site.domain())
    }

    /** Returns null on success, or the error to show. */
    suspend fun signIn(studentId: String, password: String): MailError? = withContext(Dispatchers.IO) {
        val creds = MailCredentials(studentId.trim(), password, site.domain())
        val sameStudent = credentials.mailStudentId?.equals(creds.studentId, ignoreCase = true) == true
        val keptName = if (sameStudent) state.displayName else null

        if (demo.matches(creds.studentId, password)) {
            if (!sameStudent) cache.clearAll()
            state.clear()
            state.demoMailbox = true
            state.displayName = keptName ?: demo.mailbox().displayName
            save(creds)
            return@withContext null
        }
        if (demo.appDemoActive) return@withContext MailError.AuthFailed()

        try {
            sessions.open(creds).use { session ->
                val folders = MailFolders.resolve(session.listFolders())
                val status = session.status(folders.nameOf(SpecialFolder.INBOX) ?: "INBOX")
                val name = folders.nameOf(SpecialFolder.SENT)?.let { sent ->
                    runCatching { session.newestSenderName(sent, creds.address, window = 20) }.getOrNull()
                }
                if (!sameStudent) cache.clearAll()
                state.clear()
                state.inboxUidValidity = status.uidValidity
                state.inboxSeenUidNext = status.uidNext
                state.displayName = keptName ?: name
            }
        } catch (e: Exception) {
            return@withContext MailErrors.classify(e)
        }
        save(creds)
        scheduler.schedule()
        null
    }

    /**
     * Both callers are on the main thread, so only the cheap part runs there:
     * the flags and the credential removal stay synchronous because the
     * repository's [signOuts] collector and every later `credentialsOrNull()`
     * depend on them having already happened. Deleting the cache tree and
     * cancelling the notifications are IO and finish on their own.
     */
    fun signOut() {
        scheduler.cancel()
        // Together, so a snapshot holds the departing account with its own generation or nothing.
        synchronized(accountLock) {
            generation.incrementAndGet()
            credentials.clearMailCredentials()
        }
        state.clear()
        _signedIn.value = false
        _authFailed.value = false
        _signOuts.tryEmit(Unit)
        scope.launch(Dispatchers.IO) {
            cache.clearAll()
            notifier.cancelAll()
        }
    }

    /** The server rejected the stored password: stop everything, never retry (spec §7.4). */
    fun onAuthFailure() {
        state.authFailed = true
        _authFailed.value = true
        scheduler.cancel()
    }

    private fun save(creds: MailCredentials) {
        synchronized(accountLock) {
            generation.incrementAndGet()
            credentials.mailStudentId = creds.studentId
            credentials.mailPassword = creds.password
        }
        state.authFailed = false
        _authFailed.value = false
        _signedIn.value = true
    }
}
