package org.ntust.app.tigerduck.mail

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.mail.smtp.SentCopy
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Why a mail that went out has no copy in the sent folder, in the three ways the user can act on
 * differently: there is no sent folder to keep one in, filing it failed (and may work next time),
 * or the app could not tell whether the server keeps its own and declined to guess.
 */
sealed interface SentCopyNotice {
    data object NoFolder : SentCopyNotice
    data class Failed(val error: MailError) : SentCopyNotice
    data object Unknown : SentCopyNotice

    companion object {
        /** Null when a copy is known to be in the sent folder: nothing to say. */
        fun of(copy: SentCopy): SentCopyNotice? = when (copy) {
            SentCopy.Filed, SentCopy.ServerFiledItself -> null
            SentCopy.NotAttempted -> NoFolder
            is SentCopy.Failed -> Failed(copy.error)
            SentCopy.Unknown -> Unknown
        }
    }
}

/** Where a mail went out from, and so the one screen its [SentCopyNotice] belongs on. */
sealed interface SentCopyOrigin {
    /** A new mail or a draft, which compose closes back to the list. */
    data object List : SentCopyOrigin

    /** A reply or forward of the mail [uid] in [folder], or a `mailto:` link tapped in it. */
    data class Message(val folder: String, val uid: Long) : SentCopyOrigin
}

data class PostedSentCopyNotice(val notice: SentCopyNotice, val origin: SentCopyOrigin)

/**
 * The last sent copy's notice, from the compose screen that sent the mail to the screen it goes
 * back to ([SentCopyOrigin]): the list for a new mail, the mail being answered for a reply. The
 * send succeeded, so this is never a failure of anything: it stays until the user dismisses it,
 * and nothing else clears it but the account changing -- a sign-out, or another student signing
 * in over this one -- as the next student's screens must not carry it.
 */
@Singleton
class SentCopyNotices @Inject constructor(
    account: MailAccount,
    @ApplicationScope scope: CoroutineScope,
) {
    private val _posted = MutableStateFlow<PostedSentCopyNotice?>(null)
    val posted: StateFlow<PostedSentCopyNotice?> = _posted.asStateFlow()

    private val generation = AtomicInteger()

    /**
     * Moved each time the account changes. A send reads it as it starts and hands it to [report],
     * so a send the account left while it was going out reports nothing to the next one.
     */
    val accountGeneration: Int get() = generation.get()

    init {
        // Subscribed before this returns, as the repository's own sign-out collector is.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            merge(account.signOuts, account.studentChanges).collect {
                generation.incrementAndGet()
                _posted.value = null
            }
        }
    }

    /** Posts [notice] for [origin], unless the account has changed since [accountGeneration] was read. */
    fun report(notice: SentCopyNotice, origin: SentCopyOrigin, accountGeneration: Int) {
        if (accountGeneration == generation.get()) _posted.value = PostedSentCopyNotice(notice, origin)
    }

    fun dismiss() {
        _posted.value = null
    }

    /** The notice for [origin]'s screen alone, kept current in [scope]. */
    fun noticeFor(origin: SentCopyOrigin, scope: CoroutineScope): StateFlow<SentCopyNotice?> =
        posted.map { it?.takeIf { p -> p.origin == origin }?.notice }
            .stateIn(scope, SharingStarted.Eagerly, posted.value?.takeIf { it.origin == origin }?.notice)
}
