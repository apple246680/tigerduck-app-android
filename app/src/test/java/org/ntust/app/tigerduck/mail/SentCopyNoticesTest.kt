package org.ntust.app.tigerduck.mail

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ntust.app.tigerduck.mail.store.MailCache

class SentCopyNoticesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = FakeMailServer().apply { passwords["B10000002"] = "pw2" }
    private lateinit var account: MailAccount
    private lateinit var notices: SentCopyNotices

    @Before
    fun setUp() {
        account = MailAccount(InMemoryCredentialStore(), InMemoryMailStateStore(), server.factory(), MailCache(tmp.root),
            FakeDemoGate(), schoolMailSite(), RecordingScheduler(), RecordingNotifier(), testApplicationScope())
        runBlocking { account.signIn("b10000001", "pw") }
        // Unconfined, so a sign-out's cleanup has run by the time the call that caused it returns.
        notices = SentCopyNotices(account, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
    }

    private fun report(origin: SentCopyOrigin = SentCopyOrigin.List) =
        notices.report(SentCopyNotice.NoFolder, origin, notices.accountGeneration)

    @Test
    fun `a notice carries where the mail was sent from`() {
        report(SentCopyOrigin.Message("INBOX", 5))
        assertEquals(PostedSentCopyNotice(SentCopyNotice.NoFolder, SentCopyOrigin.Message("INBOX", 5)), notices.posted.value)
    }

    @Test
    fun `signing out takes the notice with it`() {
        report()
        account.signOut()
        assertNull(notices.posted.value)
    }

    @Test
    fun `another student signing in takes the notice with it`() {
        report()
        // Over the first student's account without a sign-out in between, as the sign-in sheet
        // shown after a rejected password allows.
        runBlocking { account.signIn("b10000002", "pw2") }
        assertNull(notices.posted.value)
    }

    @Test
    fun `the same student signing in again keeps it`() {
        report()
        runBlocking { account.signIn("b10000001", "pw") }
        assertEquals(SentCopyNotice.NoFolder, notices.posted.value?.notice)
    }

    @Test
    fun `a send that began under an account that has left reports nothing`() {
        val generation = notices.accountGeneration
        account.signOut()
        notices.report(SentCopyNotice.Unknown, SentCopyOrigin.List, generation)
        assertNull(notices.posted.value)
    }
}
