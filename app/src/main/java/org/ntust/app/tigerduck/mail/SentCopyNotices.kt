package org.ntust.app.tigerduck.mail

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.mail.smtp.SentCopy
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

/**
 * The last sent copy's notice, from the compose screen that sent the mail to the screens it goes
 * back to. The send succeeded, so this is never a failure of anything: it stays until the user
 * dismisses it, and nothing else clears it but signing out, as the next student's screens must not
 * carry it.
 */
@Singleton
class SentCopyNotices @Inject constructor(
    account: MailAccount,
    @ApplicationScope scope: CoroutineScope,
) {
    private val _notice = MutableStateFlow<SentCopyNotice?>(null)
    val notice: StateFlow<SentCopyNotice?> = _notice.asStateFlow()

    init {
        // Subscribed before this returns, as the repository's own sign-out collector is.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { account.signOuts.collect { _notice.value = null } }
    }

    fun report(notice: SentCopyNotice) {
        _notice.value = notice
    }

    fun dismiss() {
        _notice.value = null
    }
}
