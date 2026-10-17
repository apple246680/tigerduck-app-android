@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck.ui.screen.mail

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.ntust.app.tigerduck.mail.FakeDemoGate
import org.ntust.app.tigerduck.mail.FakeMailServer
import org.ntust.app.tigerduck.mail.FakeSchoolMailRepository
import org.ntust.app.tigerduck.mail.InMemoryCredentialStore
import org.ntust.app.tigerduck.mail.InMemoryMailStateStore
import org.ntust.app.tigerduck.mail.MailAccount
import org.ntust.app.tigerduck.mail.MailError
import org.ntust.app.tigerduck.mail.MainDispatcherRule
import org.ntust.app.tigerduck.mail.RecordingNotifier
import org.ntust.app.tigerduck.mail.RecordingScheduler
import org.ntust.app.tigerduck.mail.cancelAndJoinBlocking
import org.ntust.app.tigerduck.mail.imap.FolderSelection
import org.ntust.app.tigerduck.mail.imap.ResolvedFolders
import org.ntust.app.tigerduck.mail.imap.SpecialFolder
import org.ntust.app.tigerduck.mail.mailSummary
import org.ntust.app.tigerduck.mail.model.FolderStatus
import org.ntust.app.tigerduck.mail.model.MailBody
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.mail.sync.CheckOutcome
import org.ntust.app.tigerduck.mail.sync.CheckSource
import org.ntust.app.tigerduck.mail.sync.MailChecker
import org.ntust.app.tigerduck.mail.testApplicationScope
import java.time.Instant
import org.ntust.app.tigerduck.mail.schoolMailSite

class SchoolMailListViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private val server = FakeMailServer()
    private val state = InMemoryMailStateStore()
    private val repo = FakeSchoolMailRepository()
    private lateinit var account: MailAccount
    private lateinit var checker: MailChecker
    private lateinit var vm: SchoolMailListViewModel

    // Where the sign-out test's cache wipe runs, stopped before TemporaryFolder deletes its folder.
    private val appScope = testApplicationScope()

    @After
    fun stopAppScope() = appScope.cancelAndJoinBlocking()

    /** The checker's clock, which dates the inbox for the "Last synced" row. */
    private var now = 0L

    @Before
    fun setUp() {
        account = MailAccount(InMemoryCredentialStore(), state, server.factory(), MailCache(tmp.root),
            FakeDemoGate(), schoolMailSite(), RecordingScheduler(), RecordingNotifier(), appScope)
        runBlocking { account.signIn("b10000001", "pw") }
        checker = MailChecker(account, state, server.factory(), RecordingNotifier(), MailCache(tmp.root)) { now }
        vm = SchoolMailListViewModel(repo, account, checker, schoolMailSite())
    }

    @Test
    fun `load shows the folders, the inbox page, and tells the checker what the page saw`() {
        repo.add("INBOX", mailSummary(1), mailSummary(2), mailSummary(3))
        repo.status = FolderStatus(server.uidValidity, 4, 3, 3)
        vm.load()
        val s = vm.state.value
        assertEquals(
            listOf(
                FolderSelection.AllMail, FolderSelection.Real("INBOX"), FolderSelection.Real("寄件備份匣"),
                FolderSelection.Real("草稿匣"), FolderSelection.Real("廣告信匣"), FolderSelection.Real("回收筒"),
            ),
            s.chips.map { it.selection },
        )
        // All mail is the one chip with no SpecialFolder behind it -- there is no such server folder.
        assertNull(s.chips.single { it.selection == FolderSelection.AllMail }.kind)
        // All mail leads the row, and it is also what the screen opens on.
        assertEquals(FolderSelection.AllMail, s.selected)
        assertEquals(listOf("Moodle 課程討論區"), s.others)
        assertEquals(listOf(3L, 2L, 1L), s.displayed.map { it.uid })
        assertTrue(s.loadState is SchoolMailListViewModel.LoadState.Loaded)
        // The inbox is part of All mail's merge, so opening on it still moves the seen marker --
        // defaulting to All mail must not cost the user the new-mail check.
        assertEquals(4L, state.inboxSeenUidNext)
    }

    @Test
    fun `when only one of Inbox or Sent resolves, there is nothing to merge and the default stays Inbox`() {
        repo.resolved = ResolvedFolders(mapOf(SpecialFolder.INBOX to "INBOX"), emptyList())
        repo.add("INBOX", mailSummary(1))
        vm.load()
        val s = vm.state.value
        assertTrue(s.chips.none { it.selection == FolderSelection.AllMail })
        assertEquals(FolderSelection.Real("INBOX"), s.selected)
    }

    @Test
    fun `unread only filters what is displayed`() {
        repo.add("INBOX", mailSummary(1, seen = true), mailSummary(2))
        vm.load()
        vm.setUnreadOnly(true)
        assertEquals(listOf(2L), vm.state.value.displayed.map { it.uid })
    }

    @Test
    fun `search shows results and says when the server could not search`() {
        repo.add("INBOX", mailSummary(1, subject = "期中考"), mailSummary(2, subject = "other"))
        vm.load()
        vm.setSearchText("期中")
        vm.submitSearch()
        assertEquals(listOf(1L), vm.state.value.displayed.map { it.uid })
        assertFalse(vm.state.value.searchLocalOnly)
        repo.searchUnsupported = true
        vm.submitSearch()
        assertTrue(vm.state.value.searchLocalOnly)
        vm.setSearchText("")
        assertEquals(2, vm.state.value.displayed.size)
    }

    @Test
    fun `swiping toggles read on the server and in the list`() {
        repo.add("INBOX", mailSummary(1))
        vm.load()
        vm.toggleRead(vm.state.value.displayed.single())
        assertEquals(listOf(Triple("INBOX", 1L, true)), repo.seenCalls)
        assertTrue(vm.state.value.displayed.single().summary.flags.seen)
    }

    @Test
    fun `selecting a folder loads it and pagination appends`() {
        repo.pageSize = 2
        repo.add("回收筒", mailSummary(7), mailSummary(8), mailSummary(9))
        vm.load()
        vm.selectFolder(FolderSelection.Real("回收筒"))
        assertEquals(listOf(9L, 8L), vm.state.value.displayed.map { it.uid })
        vm.loadMoreIfNeeded(vm.state.value.displayed.last())
        assertEquals(listOf(9L, 8L, 7L), vm.state.value.displayed.map { it.uid })
    }

    @Test
    fun `a rejected password marks the account and shows the failure`() {
        repo.loadError = MailError.AuthFailed()
        vm.load()
        assertTrue(account.authFailed.value)
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Failed)
    }

    @Test
    fun `load shows the fresh server page after the repository dropped the folder's cache`() {
        // A move/delete on the message screen that hit MailError.FolderChanged drops the
        // folder's cache in the (shared, singleton) repository; load() -- which the screen
        // always reruns on returning to the list, since LaunchedEffect restarts whenever the
        // composable re-enters composition -- must not need that cache to show the current page.
        repo.add("INBOX", mailSummary(1))
        vm.load()
        assertEquals(listOf(1L), vm.state.value.displayed.map { it.uid })

        repo.dropCache("INBOX")
        repo.add("INBOX", mailSummary(2))
        vm.load()
        assertEquals(listOf(2L, 1L), vm.state.value.displayed.map { it.uid })
    }

    @Test
    fun `polling reloads when the inbox has new mail`() {
        repo.add("INBOX", mailSummary(1))
        repo.status = FolderStatus(server.uidValidity, 2, 1, 1)
        vm.load()
        vm.startPolling()
        assertEquals(1, repo.acquired)
        repo.add("INBOX", mailSummary(2))
        repo.status = FolderStatus(server.uidValidity, 3, 2, 2)
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.POLL_MS + 1)
        main.dispatcher.scheduler.runCurrent()
        assertEquals(listOf(2L, 1L), vm.state.value.displayed.map { it.uid })
        vm.stopPolling()
        assertEquals(1, repo.released)
    }

    @Test
    fun `a password the poll finds rejected marks the account, surfaces, and stops the poll`() {
        // Spec §7.4: the page poll must not keep re-sending a rejected LOGIN every minute --
        // repeated failures can lock the school account and the campus Wi-Fi that share the password.
        repo.add("INBOX", mailSummary(1))
        vm.load()
        vm.startPolling()
        repo.statusError = MailError.AuthFailed()
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.POLL_MS + 1)
        main.dispatcher.scheduler.runCurrent()
        assertTrue(account.authFailed.value)
        assertTrue(vm.state.value.actionError is MailError.AuthFailed)
        val callsAfterRejection = repo.statusCalls

        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.POLL_MS * 5)
        main.dispatcher.scheduler.runCurrent()
        assertEquals(callsAfterRejection, repo.statusCalls)
    }

    @Test
    fun `polling does not start at all while the password is rejected, and starts again after signing in`() {
        repo.add("INBOX", mailSummary(1))
        vm.load()
        val statusCallsAfterLoad = repo.statusCalls

        account.onAuthFailure()
        vm.startPolling()
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.POLL_MS * 3)
        main.dispatcher.scheduler.runCurrent()
        assertEquals(0, repo.acquired)
        assertEquals(statusCallsAfterLoad, repo.statusCalls)

        // Signing in again clears the flag, and polling works from then on.
        runBlocking { account.signIn("b10000001", "pw") }
        assertFalse(account.authFailed.value)
        vm.startPolling()
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.POLL_MS + 1)
        main.dispatcher.scheduler.runCurrent()
        assertEquals(1, repo.acquired)
        assertEquals(statusCallsAfterLoad + 1, repo.statusCalls)
    }

    @Test
    fun `a certificate failure during a poll stays on screen instead of fading like other errors`() {
        // Unlike a busy server, an untrusted certificate says the connection to the mail server
        // could not be verified -- on a campus network, the interception case. A toast that
        // fades after a few seconds, once a minute, is one a user can miss indefinitely, so
        // this one error keeps the page in Failed (§12.3).
        repo.add("INBOX", mailSummary(1))
        vm.load()
        vm.startPolling()
        repo.statusError = MailError.Certificate()
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.POLL_MS + 1)
        main.dispatcher.scheduler.runCurrent()

        val state = vm.state.value
        assertTrue("stays on the page", state.loadState is SchoolMailListViewModel.LoadState.Failed)
        assertTrue(
            (state.loadState as SchoolMailListViewModel.LoadState.Failed).error is MailError.Certificate,
        )
        // Not routed to the transient channel, so dismissing a toast cannot clear it.
        assertNull(state.actionError)
        assertFalse(account.authFailed.value)
    }

    // --- a failed action is not a failed page -----------------------------------------------

    @Test
    fun `one flaky poll does not turn the loaded page into a failed one`() {
        // Mail2000 caps connections and starts answering "server busy" under load, so a single
        // unlucky minute is ordinary. It used to leave the header's status dot red for the rest
        // of the session: nothing reset loadState short of a successful fetchFirstPage.
        repo.add("INBOX", mailSummary(1))
        vm.load()
        vm.startPolling()
        repo.statusError = MailError.ServerBusy()
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.POLL_MS + 1)
        main.dispatcher.scheduler.runCurrent()

        assertTrue(vm.state.value.actionError is MailError.ServerBusy)
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Loaded)
        assertEquals(listOf(1L), vm.state.value.displayed.map { it.uid })

        // Shown once, then gone -- it must not linger into the next screenful.
        vm.dismissActionError()
        assertNull(vm.state.value.actionError)
    }

    @Test
    fun `a mark-read the server refuses reports as an action, not as the list failing to load`() {
        repo.add("INBOX", mailSummary(1))
        vm.load()
        repo.seenError = MailError.ServerBusy()
        vm.toggleRead(vm.state.value.displayed.single())

        assertTrue(vm.state.value.actionError is MailError.ServerBusy)
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Loaded)
        // The row is unchanged, and the page it lives on is still the page that loaded fine.
        assertFalse(vm.state.value.displayed.single().summary.flags.seen)
    }

    @Test
    fun `an empty folder whose mark-read failed still reads as empty, not as unloadable`() {
        // Nothing to toggle here, but the same failure used to replace a perfectly good empty
        // folder with the "couldn't load" state, because displayed.isEmpty() && failed != null.
        vm.load()
        assertTrue(vm.state.value.displayed.isEmpty())
        repo.statusError = MailError.Network()
        vm.startPolling()
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.POLL_MS + 1)
        main.dispatcher.scheduler.runCurrent()

        assertTrue(vm.state.value.actionError is MailError.Network)
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Loaded)
    }

    @Test
    fun `a rejected password still reaches the account from a mark-read, action error or not`() {
        // Spec §7.4: whichever call happens to meet the rejected password, the account has to
        // hear about it -- the lockout protection must not depend on that being a load.
        repo.add("INBOX", mailSummary(1))
        vm.load()
        repo.seenError = MailError.AuthFailed()
        vm.toggleRead(vm.state.value.displayed.single())

        assertTrue(account.authFailed.value)
        assertTrue(vm.state.value.actionError is MailError.AuthFailed)
    }

    @Test
    fun `signing out clears the previous account's mail from the screen`() {
        repo.add("INBOX", mailSummary(1), mailSummary(2))
        vm.load()
        assertEquals(2, vm.state.value.displayed.size)
        account.signOut()
        main.dispatcher.scheduler.runCurrent()
        assertEquals(SchoolMailListViewModel.UiState(), vm.state.value)
    }

    // --- the "Last synced" row --------------------------------------------------------------

    @Test
    fun `loading the folders on screen dates them, and a load that fails leaves the date`() {
        now = 5_000
        repo.add("INBOX", mailSummary(1))
        vm.load()
        // All mail: the inbox and Sent, both read just now.
        assertEquals(5_000L, vm.state.value.fetchedAt)
        assertEquals(5_000L, state.fetchedAt("INBOX"))
        assertEquals(5_000L, state.fetchedAt("寄件備份匣"))

        now = 9_000
        repo.loadError = MailError.Network()
        vm.refresh()
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Failed)
        assertEquals(5_000L, vm.state.value.fetchedAt)
        assertEquals(5_000L, state.fetchedAt("INBOX"))
        assertEquals(5_000L, state.fetchedAt("寄件備份匣"))
    }

    /**
     * Each folder is dated on its own, and the row dates the folders on screen: the one
     * selected, or for All mail the older of its two, as on the iPhone. Here a poll's check of
     * Sent fails, so the inbox is dated and Sent is not, and All mail shows Sent's older date.
     */
    @Test
    fun `the date is the folder on screen's, and All mail shows the older of its two`() {
        now = 5_000
        repo.add("INBOX", mailSummary(1))
        repo.add("寄件備份匣", mailSummary(1))
        repo.status = FolderStatus(server.uidValidity, 2, 1, 1)
        vm.load()
        assertEquals(FolderSelection.AllMail, vm.state.value.selected)
        assertEquals(5_000L, vm.state.value.fetchedAt)

        vm.startPolling()
        now = 9_000
        repo.folderStatusErrors["寄件備份匣"] = MailError.ServerBusy()
        pollOnce()
        assertEquals(9_000L, state.fetchedAt("INBOX"))
        assertEquals(5_000L, state.fetchedAt("寄件備份匣"))
        assertEquals(5_000L, vm.state.value.fetchedAt)
        vm.stopPolling()

        vm.selectFolder(FolderSelection.Real("INBOX"))
        assertEquals(9_000L, vm.state.value.fetchedAt)

        now = 12_000
        vm.selectFolder(FolderSelection.Real("寄件備份匣"))
        assertEquals(12_000L, vm.state.value.fetchedAt)
        assertEquals(9_000L, state.fetchedAt("INBOX"))

        vm.selectFolder(FolderSelection.AllMail)
        assertEquals(12_000L, vm.state.value.fetchedAt)
    }

    /**
     * A poll that finds nothing new must still move the row, or a user would read the time as
     * stale and think the page had not synced -- in All mail too. So the poll asks every folder on
     * screen for its STATUS, the inbox for new mail as always and Sent for nothing more than an
     * answer, and dates each that answered; one whose check failed keeps its date.
     */
    @Test
    fun `in All mail a poll that finds nothing new moves the visible date, having verified Sent too`() {
        now = 5_000
        repo.add("INBOX", mailSummary(1))
        repo.add("寄件備份匣", mailSummary(1))
        repo.status = FolderStatus(server.uidValidity, 2, 1, 1)
        vm.load()
        vm.startPolling()

        now = 9_000
        pollOnce()
        assertEquals(listOf("寄件備份匣"), repo.folderStatusCalls)
        assertEquals(9_000L, state.fetchedAt("INBOX"))
        assertEquals(9_000L, state.fetchedAt("寄件備份匣"))
        assertEquals(9_000L, vm.state.value.fetchedAt)
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Loaded)

        now = 12_000
        repo.folderStatusErrors["寄件備份匣"] = MailError.ServerBusy()
        pollOnce()
        assertEquals(12_000L, state.fetchedAt("INBOX"))
        assertEquals(9_000L, state.fetchedAt("寄件備份匣"))
        assertEquals(9_000L, vm.state.value.fetchedAt)
        vm.stopPolling()
    }

    /** A folder viewed on its own is verified by the poll too; the inbox, off screen, is not asked. */
    @Test
    fun `a folder viewed on its own is verified by the poll, and dated when it answers`() {
        now = 5_000
        repo.add("INBOX", mailSummary(1))
        repo.add("寄件備份匣", mailSummary(1))
        vm.load()
        vm.selectFolder(FolderSelection.Real("寄件備份匣"))
        vm.startPolling()
        val inboxChecks = repo.statusCalls

        now = 9_000
        pollOnce()
        assertEquals(listOf("寄件備份匣"), repo.folderStatusCalls)
        assertEquals(inboxChecks, repo.statusCalls)
        assertEquals(9_000L, state.fetchedAt("寄件備份匣"))
        assertEquals(5_000L, state.fetchedAt("INBOX"))
        assertEquals(9_000L, vm.state.value.fetchedAt)
        vm.stopPolling()
    }

    /**
     * A load still out across a sign-out and another account's sign-in is the first account's:
     * its rows must not go on the second account's screen, nor its dates on folders nothing was
     * fetched for (spec §7.5). The load is not cancelled -- it is blocking IMAP I/O -- so its
     * answer is dropped when it lands.
     */
    @Test
    fun `a load that lands after a sign-out and another sign-in shows and dates nothing`() {
        now = 5_000
        repo.add("INBOX", mailSummary(1))
        repo.loadGate = CompletableDeferred()
        vm.load()
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Loading)

        account.signOut()
        main.dispatcher.scheduler.runCurrent()
        server.passwords["B10000002"] = "pw"
        runBlocking { account.signIn("b10000002", "pw") }
        repo.loadGate!!.complete(Unit)

        assertEquals(SchoolMailListViewModel.UiState(), vm.state.value)
        assertEquals(0L, state.fetchedAt("INBOX"))
        assertEquals(0L, state.fetchedAt("寄件備份匣"))
    }

    /** Each folder is dated as its own page lands, so a failure of Sent's does not lose the inbox's. */
    @Test
    fun `in All mail a folder that loads is dated even when the other one fails`() {
        now = 5_000
        repo.add("INBOX", mailSummary(1))
        repo.folderLoadErrors["寄件備份匣"] = MailError.Network()
        vm.load()

        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Failed)
        assertEquals(5_000L, state.fetchedAt("INBOX"))
        assertEquals(0L, state.fetchedAt("寄件備份匣"))
        // All mail is as fresh as its staler half, which never loaded.
        assertEquals(0L, vm.state.value.fetchedAt)
    }

    /**
     * A chip tapped while a load is out does not lose the date of a page already on its way
     * back -- and the batch sends nothing more for the view that left.
     */
    @Test
    fun `a page that lands after the chip moved on still dates its folder`() {
        now = 5_000
        repo.add("INBOX", mailSummary(1))
        repo.add("寄件備份匣", mailSummary(1))
        repo.onLoaded = { folder ->
            if (folder == "INBOX") {
                repo.onLoaded = null
                now = 9_000
                vm.selectFolder(FolderSelection.Real("寄件備份匣"))
            }
        }
        vm.load()

        assertEquals(FolderSelection.Real("寄件備份匣"), vm.state.value.selected)
        assertEquals(9_000L, state.fetchedAt("INBOX"))
        assertEquals(9_000L, state.fetchedAt("寄件備份匣"))
        assertEquals(9_000L, vm.state.value.fetchedAt)
        assertEquals(listOf(1L), vm.state.value.messages.map { it.uid })
        // The inbox page, then Sent's own load; All mail's Sent request was never sent.
        assertEquals(listOf("INBOX", "寄件備份匣"), repo.loadsSent)
    }

    /** The inbox answered the poll's STATUS; that it had grown and the refetch then failed takes nothing from that. */
    @Test
    fun `a poll whose STATUS finds new mail dates the inbox even when the reload then fails`() {
        now = 5_000
        repo.add("INBOX", mailSummary(1))
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        vm.startPolling()

        now = 9_000
        repo.status = FolderStatus(server.uidValidity, 5, 3, 3)
        repo.loadError = MailError.Network()
        pollOnce()

        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Failed)
        assertEquals(9_000L, state.fetchedAt("INBOX"))
        assertEquals(9_000L, vm.state.value.fetchedAt)
        vm.stopPolling()
    }

    /** A chip tapped while the poll's inbox STATUS is out: the poll asks nothing more for the view that left. */
    @Test
    fun `a poll does not STATUS a folder that left the screen while its inbox check was out`() {
        now = 5_000
        repo.add("INBOX", mailSummary(1))
        repo.add("寄件備份匣", mailSummary(1))
        repo.status = FolderStatus(server.uidValidity, 2, 1, 1)
        vm.load()
        vm.startPolling()
        repo.statusGate = CompletableDeferred()
        now = 9_000
        pollOnce()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        repo.statusGate!!.complete(Unit)

        assertEquals(emptyList<String>(), repo.folderStatusCalls)
        assertEquals(9_000L, state.fetchedAt("INBOX"))
        vm.stopPolling()
    }

    /**
     * A's All mail load has its inbox page when A signs out and B signs in. The batch's next
     * request would go out with B's credentials under A's generation, and B's answer -- a failure
     * too, even a rejected password -- would be dropped as A's. So a batch stops before each
     * further request once its account has left, and sends nothing more.
     */
    @Test
    fun `a batch whose account left between two of its requests sends no more`() {
        now = 5_000
        repo.add("INBOX", mailSummary(1))
        repo.add("寄件備份匣", mailSummary(1))
        repo.onLoaded = { folder ->
            if (folder == "INBOX") {
                repo.onLoaded = null
                signOutAndSignInAnother()
            }
        }
        vm.load()

        assertEquals(listOf("INBOX"), repo.loadsSent)
        assertEquals(SchoolMailListViewModel.UiState(), vm.state.value)
        assertEquals(0L, state.fetchedAt("INBOX"))
        assertEquals(0L, state.fetchedAt("寄件備份匣"))
    }

    /** The folder list a load starts with is its first request, and waits for the session like any other. */
    @Test
    fun `a load whose folder list waits across the account change sends it for nobody`() {
        repo.add("INBOX", mailSummary(1))
        repo.foldersGate = CompletableDeferred()
        vm.load()
        signOutAndSignInAnother()
        repo.foldersGate!!.complete(Unit)

        assertEquals(0, repo.foldersSent)
        assertEquals(emptyList<String>(), repo.loadsSent)
        assertEquals(SchoolMailListViewModel.UiState(), vm.state.value)
    }

    /** A search is a batch too, one SEARCH per folder of the view. */
    @Test
    fun `a search whose account left between two of its folders sends no more`() {
        repo.add("INBOX", mailSummary(1, subject = "期中考"))
        repo.add("寄件備份匣", mailSummary(2, subject = "期中考"))
        vm.load()
        repo.onSearched = { folder ->
            if (folder == "INBOX") {
                repo.onSearched = null
                signOutAndSignInAnother()
            }
        }
        vm.setSearchText("期中考")
        vm.submitSearch()

        assertEquals(listOf("INBOX"), repo.searchesSent)
        assertEquals(SchoolMailListViewModel.UiState(), vm.state.value)
    }

    /**
     * The poll's check of Sent passed, but its STATUS then queued behind another request holding
     * the session, and the user moved to Inbox meanwhile. Asked again with the session held, right
     * before the command, the STATUS is not sent for a folder that has left the screen.
     */
    @Test
    fun `a poll's STATUS queued behind the session is not sent once its folder left the screen`() {
        now = 5_000
        repo.add("INBOX", mailSummary(1))
        repo.add("寄件備份匣", mailSummary(1))
        repo.status = FolderStatus(server.uidValidity, 2, 1, 1)
        vm.load()
        vm.startPolling()
        repo.folderStatusGate = CompletableDeferred()
        now = 9_000
        pollOnce()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        repo.folderStatusGate!!.complete(Unit)

        assertEquals(emptyList<String>(), repo.folderStatusCalls)
        assertEquals(9_000L, state.fetchedAt("INBOX"))
        assertEquals(5_000L, state.fetchedAt("寄件備份匣"))
        vm.stopPolling()
    }

    /** The account leaves and another arrives, as Settings does it, in the middle of whatever is running. */
    private fun signOutAndSignInAnother() {
        account.signOut()
        main.dispatcher.scheduler.runCurrent()
        server.passwords["B10000002"] = "pw"
        runBlocking { account.signIn("b10000002", "pw") }
    }

    @Test
    fun `a poll that finds nothing new still dates the inbox, and one that fails does not`() {
        repo.add("INBOX", mailSummary(1))
        repo.status = FolderStatus(server.uidValidity, 2, 1, 1)
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        vm.startPolling()

        now = 9_000
        pollOnce()
        assertEquals(9_000L, vm.state.value.fetchedAt)

        now = 12_000
        repo.statusError = MailError.ServerBusy()
        pollOnce()
        assertEquals(9_000L, vm.state.value.fetchedAt)
        vm.stopPolling()
    }

    @Test
    fun `a check that ran while the page was away shows when the page comes back`() {
        repo.add("INBOX", mailSummary(1))
        vm.load()
        vm.selectFolder(FolderSelection.Real("INBOX"))
        now = 7_000
        assertEquals(CheckOutcome.NoChange, runBlocking { checker.check(CheckSource.ALARM) })

        vm.startPolling()
        assertEquals(7_000L, vm.state.value.fetchedAt)
        vm.stopPolling()
    }

    @Test
    fun `the demo mailbox is never dated, having no server to answer`() {
        runBlocking { account.signIn("B10000099", "demo") }
        now = 5_000
        repo.add("INBOX", mailSummary(1))
        vm.load()
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Loaded)
        assertEquals(0L, vm.state.value.fetchedAt)
        assertEquals(0L, state.fetchedAt("INBOX"))
        assertEquals(0L, state.fetchedAt("寄件備份匣"))
    }

    @Test
    fun `signing out forgets every folder's date with the rest of the mail state`() {
        now = 5_000
        repo.add("INBOX", mailSummary(1))
        vm.load()
        assertEquals(5_000L, vm.state.value.fetchedAt)

        account.signOut()
        main.dispatcher.scheduler.runCurrent()
        assertEquals(0L, vm.state.value.fetchedAt)
        assertEquals(0L, state.fetchedAt("INBOX"))
        assertEquals(0L, state.fetchedAt("寄件備份匣"))
    }

    // --- a new mail's body ---------------------------------------------------------------

    /** One poll tick. Not advanceUntilIdle: the poll loop never idles, it delays again. */
    private fun pollOnce() {
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.POLL_MS + 1)
        main.dispatcher.scheduler.runCurrent()
    }

    /** Replaces the inbox with exactly [uids], and the status a server would report for it. */
    private fun inboxOf(vararg uids: Long) {
        repo.mail.getOrPut("INBOX") { mutableListOf() }.apply {
            clear()
            addAll(uids.map { mailSummary(it) })
        }
        repo.status = FolderStatus(server.uidValidity, uids.max() + 1, uids.size, uids.size)
    }

    @Test
    fun `a poll that finds new mail warms its body, so tapping it does not spin`() {
        // Spec §5. MailChecker does this on the connection its own check holds, but the page poll
        // never goes through MailChecker -- it calls fetchFirstPage itself. So in the one case
        // where the mail is certain to be opened within seconds, the app already on this list,
        // the row arrived within the minute and tapping it still spun.
        inboxOf(1)
        vm.load()
        vm.startPolling()
        repo.bodyCalls.clear()

        repo.bodies[2] = MailBody(null, "hello", emptyList(), emptyMap())
        inboxOf(1, 2)
        pollOnce()

        assertEquals(listOf("INBOX" to 2L), repo.bodyCalls)
    }

    @Test
    fun `a burst of new mail warms the newest five and nothing the list already had`() {
        // Bounded for the same reason MailChecker's is: a burst must not turn one poll into a
        // long one on a connection the screen is waiting behind.
        inboxOf(1)
        vm.load()
        vm.startPolling()
        repo.bodyCalls.clear()

        inboxOf(*(1L..9L).toList().toLongArray())
        pollOnce()

        assertEquals(listOf(9L, 8L, 7L, 6L, 5L), repo.bodyCalls.map { it.second })
    }

    @Test
    fun `a body that will not come down leaves the poll's page exactly as it was`() {
        inboxOf(1)
        vm.load()
        vm.startPolling()
        repo.bodyCalls.clear()
        repo.bodyError = MailError.ServerBusy()

        inboxOf(1, 2)
        pollOnce()

        // The fetch was actually attempted -- without this the test would pass just as well with
        // the prefetch deleted outright.
        assertEquals(listOf("INBOX" to 2L), repo.bodyCalls)
        assertEquals(listOf(2L, 1L), vm.state.value.displayed.map { it.uid })
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Loaded)
        assertNull(vm.state.value.actionError)
    }

    @Test
    fun `a poll that finds nothing new warms nothing`() {
        inboxOf(1)
        vm.load()
        vm.startPolling()
        repo.bodyCalls.clear()

        pollOnce()

        assertTrue(repo.bodyCalls.isEmpty())
    }

    // --- prefetch --------------------------------------------------------------------------

    @Test
    fun `entering the page warms the other folders at twenty rows each`() = runTest {
        vm.load()
        advanceUntilIdle()

        val prefetched = repo.pageLimits.filter { it.second == SchoolMailListViewModel.PREFETCH_LIMIT }
        // The real decoded names (see SpecialFolder): DRAFTS, JUNK, TRASH -- everything but the
        // two folders All mail already merged in (INBOX, SENT).
        assertEquals(
            setOf("草稿匣", "廣告信匣", "回收筒"),
            prefetched.map { it.first }.toSet(),
        )
    }

    @Test
    fun `the visible folders are loaded at full size, not the prefetch size`() = runTest {
        vm.load()
        advanceUntilIdle()

        val first = repo.pageLimits.first()
        assertEquals(50, first.second)
    }

    @Test
    fun `no folder is fetched twice`() = runTest {
        vm.load()
        advanceUntilIdle()

        val names = repo.pageLimits.map { it.first }
        assertEquals(names.size, names.distinct().size)
    }

    @Test
    fun `a prefetch failure never reaches the page`() = runTest {
        repo.prefetchError = MailError.ServerBusy()
        vm.load()
        advanceUntilIdle()

        // The failure actually happened to every folder in the queue -- proof it was
        // swallowed, not just that prefetch never got a chance to run at all.
        val prefetched = repo.pageLimits.filter { it.second == SchoolMailListViewModel.PREFETCH_LIMIT }
        assertEquals(setOf("草稿匣", "廣告信匣", "回收筒"), prefetched.map { it.first }.toSet())

        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Loaded)
        assertNull(vm.state.value.actionError)
    }

    @Test
    fun `switching folder cancels the prefetch in flight`() = runTest {
        repo.blockPrefetch = true
        vm.load()
        advanceUntilIdle()
        val before = repo.pageLimits.size

        vm.selectFolder(FolderSelection.Real("草稿匣"))
        advanceUntilIdle()
        repo.blockPrefetch = false
        advanceUntilIdle()

        // The switch's own load comes first, at full size and never queued behind the warm.
        assertEquals("草稿匣" to 50, repo.pageLimits[before])
        // The parked call really was cancelled rather than merely abandoned: it released its slot
        // inside loadPage before the switch's own load entered it, so no two loads were ever open
        // at once. A prefetch left running would have overlapped that load and made this 2.
        assertEquals(1, repo.maxConcurrentLoads)
        // And the cancelled queue did not carry on beside the one the switch starts: it would
        // have gone on to 廣告信匣 and 回收筒, which the new queue also covers, so a survivor
        // shows up as a folder warmed twice.
        val prefetched = repo.pageLimits.filter { it.second == SchoolMailListViewModel.PREFETCH_LIMIT }
        assertEquals(prefetched.size, prefetched.distinct().size)
    }

    @Test
    fun `switching folder warms whatever the new view is not showing`() = runTest {
        // startPrefetch used to run from load() alone, and load() is keyed on (signedIn,
        // authFailed) -- so the first chip tap cancelled the warm and nothing ever started it
        // again. A user who tapped a chip early left every other mailbox cold for the rest of
        // this view model's life, which is the feature quietly switching itself off.
        vm.load()
        advanceUntilIdle()
        val before = repo.pageLimits.size

        vm.selectFolder(FolderSelection.Real("草稿匣"))
        advanceUntilIdle()

        val after = repo.pageLimits.drop(before)
        assertEquals("草稿匣" to 50, after.first())
        assertEquals(
            setOf("INBOX", "寄件備份匣", "廣告信匣", "回收筒"),
            after.filter { it.second == SchoolMailListViewModel.PREFETCH_LIMIT }.map { it.first }.toSet(),
        )
    }

    @Test
    fun `the prefetch never has two loadPage calls open at the same time`() = runTest {
        // Mail2000 caps connections and answers "server busy" under load -- a fan-out here
        // (e.g. one async{} per folder) would be paid for by the screen the user is actually
        // reading. blockPrefetch parks a call *inside* loadPage rather than before it, so a
        // fan-out implementation gets the chance to start a second call while the first is
        // still open -- which is exactly what maxConcurrentLoads would catch.
        repo.blockPrefetch = true
        vm.load()
        advanceUntilIdle()
        repo.blockPrefetch = false
        advanceUntilIdle()

        assertEquals(1, repo.maxConcurrentLoads)
    }

    // --- All mail ------------------------------------------------------------------------

    private fun at(millis: Long) = Instant.ofEpochMilli(millis)

    private fun selectAll() = vm.selectFolder(FolderSelection.AllMail)

    @Test
    fun `All mail merges only the inbox and sent folders, newest first`() {
        repo.add("INBOX", mailSummary(1, sentAt = at(300)), mailSummary(2, sentAt = at(100)))
        repo.add(SENT, mailSummary(5, sentAt = at(200)))
        // The folders the merge deliberately leaves out: drafts, junk, trash, user folders.
        repo.add("草稿匣", mailSummary(60, sentAt = at(999)))
        repo.add("廣告信匣", mailSummary(61, sentAt = at(999)))
        repo.add("回收筒", mailSummary(62, sentAt = at(999)))
        repo.add("Moodle 課程討論區", mailSummary(63, sentAt = at(999)))
        vm.load()
        selectAll()

        val rows = vm.state.value.displayed
        assertEquals(listOf("INBOX" to 1L, SENT to 5L, "INBOX" to 2L), rows.map { it.folder to it.uid })
    }

    @Test
    fun `the same UID in two folders is two separate rows, and acting on one leaves the other alone`() {
        // UIDs are unique only within a folder: Inbox and Sent both holding a UID 42 is
        // ordinary, and a list keyed by UID alone would collapse them into one row.
        repo.add("INBOX", mailSummary(42, subject = "收到的", sentAt = at(200)))
        repo.add(SENT, mailSummary(42, subject = "寄出的", sentAt = at(100)))
        vm.load()
        selectAll()

        val rows = vm.state.value.displayed
        assertEquals(2, rows.size)
        assertEquals(2, rows.map { it.key }.toSet().size)
        assertEquals(listOf("收到的", "寄出的"), rows.map { it.summary.subject })

        vm.toggleRead(rows.last())
        val after = vm.state.value.displayed
        assertFalse(after.first { it.folder == "INBOX" }.summary.flags.seen)
        assertTrue(after.first { it.folder == SENT }.summary.flags.seen)
    }

    @Test
    fun `a Sent row acted on through All mail addresses Sent, not the selected chip`() {
        repo.add("INBOX", mailSummary(1, sentAt = at(100)))
        repo.add(SENT, mailSummary(7, sentAt = at(200)))
        vm.load()
        selectAll()

        val sentRow = vm.state.value.displayed.single { it.folder == SENT }
        vm.toggleRead(sentRow)
        assertEquals(listOf(Triple(SENT, 7L, true)), repo.seenCalls)
        // And the screen sends the tap to that row's own folder, drafts check included.
        assertEquals(SpecialFolder.SENT, vm.state.value.kindOf(sentRow.folder))
        assertEquals(SpecialFolder.INBOX, vm.state.value.kindOf("INBOX"))
    }

    @Test
    fun `no repository call in the merged view is ever given a folder the server does not have`() {
        // All mail has no server-side existence at all -- a synthetic name reaching an IMAP
        // SELECT is the failure this whole design exists to make unrepresentable.
        repo.pageSize = 1
        repo.add("INBOX", mailSummary(1, subject = "期中考", sentAt = at(100)), mailSummary(2, sentAt = at(400)))
        repo.add(SENT, mailSummary(9, subject = "期中考", sentAt = at(200)), mailSummary(10, sentAt = at(300)))
        vm.load()
        selectAll()
        vm.loadMoreIfNeeded(vm.state.value.messages.last())
        vm.setSearchText("期中考")
        vm.submitSearch()
        vm.toggleRead(vm.state.value.displayed.first())
        vm.refresh()

        val realNames = (SpecialFolder.entries.map { it.decodedName } + repo.resolved.others).toSet()
        assertEquals(emptyList<String>(), repo.foldersTouched.filterNot { it in realNames })
        // …and it really did read both merged folders, so the check above isn't vacuous.
        assertTrue(repo.foldersTouched.containsAll(listOf("INBOX", SENT)))
    }

    @Test
    fun `the merged list keeps its own cursor per folder and ends only once both have run out`() {
        repo.pageSize = 1
        repo.add("INBOX", mailSummary(1, sentAt = at(100)), mailSummary(2, sentAt = at(400)))
        repo.add(SENT, mailSummary(9, sentAt = at(200)), mailSummary(10, sentAt = at(300)))
        vm.load()
        selectAll()

        // One page per folder, merged by date -- two round trips, not one per folder per scroll.
        assertEquals(listOf("INBOX" to 2L, SENT to 10L), vm.state.value.messages.map { it.folder to it.uid })
        assertEquals(mapOf("INBOX" to 1, SENT to 1), vm.state.value.cursors)

        vm.loadMoreIfNeeded(vm.state.value.messages.last())
        assertEquals(
            listOf("INBOX" to 2L, SENT to 10L, SENT to 9L, "INBOX" to 1L),
            vm.state.value.messages.map { it.folder to it.uid },
        )
        // Both folders are exhausted, so the list is genuinely at its end.
        assertTrue(vm.state.value.cursors.isEmpty())
    }

    @Test
    fun `a folder that runs out first does not end the merged list while the other still has mail`() {
        repo.pageSize = 1
        repo.add("INBOX", mailSummary(1, sentAt = at(100)), mailSummary(2, sentAt = at(300)))
        repo.add(SENT, mailSummary(9, sentAt = at(200)))
        vm.load()
        selectAll()

        assertEquals(mapOf("INBOX" to 1), vm.state.value.cursors)
        vm.loadMoreIfNeeded(vm.state.value.messages.last())
        assertEquals(listOf(2L, 9L, 1L), vm.state.value.messages.map { it.uid })
        assertTrue(vm.state.value.cursors.isEmpty())
    }

    @Test
    fun `searching All mail covers both folders and still reports a local-only fallback`() {
        repo.add("INBOX", mailSummary(1, subject = "期中考通知", sentAt = at(200)), mailSummary(2, subject = "other", sentAt = at(100)))
        repo.add(SENT, mailSummary(9, subject = "期中考回覆", sentAt = at(100)))
        vm.load()
        selectAll()
        vm.setSearchText("期中考")
        vm.submitSearch()

        assertEquals(listOf("INBOX" to 1L, SENT to 9L), vm.state.value.displayed.map { it.folder to it.uid })
        assertFalse(vm.state.value.searchLocalOnly)

        repo.searchUnsupported = true
        vm.submitSearch()
        assertTrue(vm.state.value.searchLocalOnly)
    }

    @Test
    fun `All mail keeps checking for new mail and moving the seen marker, same as Inbox always did`() {
        // Inbox is one of the folders All mail merges, so the inbox is genuinely on screen while
        // viewing All mail -- defaulting to it must not cost the user the new-mail check or
        // silently let a notification re-fire for mail the merged list already showed.
        repo.add("INBOX", mailSummary(1, sentAt = at(100)))
        repo.add(SENT, mailSummary(9, sentAt = at(200)))
        repo.status = FolderStatus(server.uidValidity, 2, 1, 1)
        vm.load()
        assertEquals(FolderSelection.AllMail, vm.state.value.selected)
        val statusCallsAfterLoad = repo.statusCalls

        vm.startPolling()
        repo.add("INBOX", mailSummary(2, sentAt = at(300)))
        repo.status = FolderStatus(server.uidValidity, 3, 2, 2)
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.POLL_MS + 1)
        main.dispatcher.scheduler.runCurrent()

        assertTrue(repo.statusCalls > statusCallsAfterLoad)
        assertEquals(3L, state.inboxSeenUidNext)
        // The merged list itself picked up the new inbox mail, same as Inbox always refreshed.
        assertEquals(listOf("INBOX" to 2L, SENT to 9L, "INBOX" to 1L), vm.state.value.messages.map { it.folder to it.uid })
    }

    @Test
    fun `All mail paints from each folder's cache before the server answers`() {
        repo.add("INBOX", mailSummary(1, sentAt = at(200)))
        repo.add(SENT, mailSummary(9, sentAt = at(100)))
        vm.load()
        selectAll()
        assertEquals(2, vm.state.value.messages.size)

        // Back to the inbox and out again: both folders are cached now, so the merge is on
        // screen before the refresh lands -- and the refresh is still one page per folder.
        vm.selectFolder(FolderSelection.Real("INBOX"))
        repo.loadError = MailError.Network()
        selectAll()
        assertEquals(listOf("INBOX" to 1L, SENT to 9L), vm.state.value.messages.map { it.folder to it.uid })
        assertTrue(vm.state.value.loadState is SchoolMailListViewModel.LoadState.Failed)
    }

    private companion object {
        const val SENT = "寄件備份匣"
    }

    @Test
    fun `a warm never shortens a folder cache that already holds more`() = runTest {
        // More mail than one warm brings back, so a warm's page really is the shorter one.
        repo.add("草稿匣", *(1..30).map { mailSummary(it.toLong()) }.toTypedArray())

        // Opening the folder caches a full foreground page.
        vm.selectFolder(FolderSelection.Real("草稿匣"))
        advanceUntilIdle()
        val full = repo.cachedPage("草稿匣")!!.messages.size
        assertTrue("fixture must hold more than a prefetch page", full > SchoolMailListViewModel.PREFETCH_LIMIT)

        // Going back to All mail warms 草稿匣 again, at twenty rows.
        vm.selectFolder(FolderSelection.AllMail)
        advanceUntilIdle()

        // Twenty rows must not be what is left on disk for an offline open.
        assertEquals(full, repo.cachedPage("草稿匣")!!.messages.size)
    }

    @Test
    fun `a warm still seeds a folder that has no cache at all`() = runTest {
        vm.load()
        advanceUntilIdle()

        // 廣告信匣 is not in All mail's merge, so only the warm can have populated it.
        assertNotNull(repo.cachedPage("廣告信匣"))
    }

    @Test
    fun `a refresh stops the warm queue and starts a new one once it lands`() = runTest {
        repo.blockPrefetch = true
        vm.load()
        advanceUntilIdle()
        val before = repo.pageLimits.size

        vm.refresh()
        advanceUntilIdle()
        repo.blockPrefetch = false
        advanceUntilIdle()

        // The refresh's own load went first, at full size.
        assertEquals(50, repo.pageLimits[before].second)
        // Never two loads inside the repository at once: the parked warm really was cancelled.
        assertEquals(1, repo.maxConcurrentLoads)
        // And warming resumed afterwards rather than ending for the view model's life -- the bug
        // that cancelling without restarting would have reintroduced.
        val after = repo.pageLimits.drop(before + 1).filter { it.second == SchoolMailListViewModel.PREFETCH_LIMIT }
        assertEquals(setOf("草稿匣", "廣告信匣", "回收筒"), after.map { it.first }.toSet())
    }

    @Test
    fun `the warm holds off, so a refresh in the first moments queues behind nothing`() = runTest {
        vm.load()
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.PREFETCH_START_DELAY_MS / 2)

        // Inside the grace the connection has not been taken for warming at all, so a cancel
        // landing here costs a foreground request nothing -- not even the one page it would
        // otherwise have had to wait out.
        assertEquals(0, repo.pageLimits.count { it.second == SchoolMailListViewModel.PREFETCH_LIMIT })

        advanceUntilIdle()
        // And the warm does still happen once the moment has passed.
        assertEquals(3, repo.pageLimits.count { it.second == SchoolMailListViewModel.PREFETCH_LIMIT })
    }

    @Test
    fun `leaving before the warm starts does not leave the other mailboxes cold`() {
        val warm = { repo.pageLimits.filter { it.second == SchoolMailListViewModel.PREFETCH_LIMIT } }
        vm.load()
        // Still inside the start grace, so the queue is parked and has touched nothing.
        assertEquals(0, warm().size)

        // Leaving here is what following a notification straight back out looks like.
        vm.stopPolling()
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.PREFETCH_START_DELAY_MS + 1)
        main.dispatcher.scheduler.runCurrent()
        assertEquals(0, warm().size)

        // Coming back has to re-arm it; nothing else would until a load, refresh or folder tap.
        vm.startPolling()
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.PREFETCH_START_DELAY_MS + 1)
        main.dispatcher.scheduler.runCurrent()
        assertEquals(setOf("草稿匣", "廣告信匣", "回收筒"), warm().map { it.first }.toSet())
        vm.stopPolling()
    }

    @Test
    fun `a resume with nothing left to warm does not go back to the server`() {
        val warm = { repo.pageLimits.filter { it.second == SchoolMailListViewModel.PREFETCH_LIMIT } }
        vm.load()
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.PREFETCH_START_DELAY_MS + 1)
        main.dispatcher.scheduler.runCurrent()
        assertEquals(3, warm().size)

        vm.stopPolling()
        vm.startPolling()
        main.dispatcher.scheduler.advanceTimeBy(SchoolMailListViewModel.PREFETCH_START_DELAY_MS + 1)
        main.dispatcher.scheduler.runCurrent()

        // The queue ran out before the pause, so resuming must not warm the same three again.
        assertEquals(3, warm().size)
        vm.stopPolling()
    }
}
