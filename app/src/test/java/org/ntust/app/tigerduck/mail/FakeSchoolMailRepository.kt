package org.ntust.app.tigerduck.mail

import kotlinx.coroutines.CompletableDeferred
import org.ntust.app.tigerduck.mail.compose.OutgoingMail
import org.ntust.app.tigerduck.mail.imap.ResolvedFolders
import org.ntust.app.tigerduck.mail.imap.SpecialFolder
import org.ntust.app.tigerduck.mail.model.FolderStatus
import org.ntust.app.tigerduck.mail.model.MailAddress
import org.ntust.app.tigerduck.mail.model.MailBody
import org.ntust.app.tigerduck.mail.model.MailFlags
import org.ntust.app.tigerduck.mail.model.MailPage
import org.ntust.app.tigerduck.mail.model.MailSummary
import org.ntust.app.tigerduck.mail.smtp.SentCopy
import java.io.OutputStream

/** Synchronous, in-memory [SchoolMailRepository] for ViewModel tests. `beforeSeq` is an offset token here. */
class FakeSchoolMailRepository : SchoolMailRepository {
    var resolved = ResolvedFolders(SpecialFolder.entries.associateWith { it.decodedName }, listOf("Moodle 課程討論區"))
    val mail = mutableMapOf<String, MutableList<MailSummary>>()
    var pageSize = 50
    var status = FolderStatus(1, 1, 0, 0)

    /** Thrown by [inboxStatus] -- the first call the page poll makes. */
    var statusError: MailError? = null
    var statusCalls = 0

    /** Set, every [inboxStatus] waits on it first: a poll's check held out while the test acts. */
    var statusGate: CompletableDeferred<Unit>? = null

    /** Set, every [status] waits on it first: a STATUS queued behind the session another request holds. */
    var folderStatusGate: CompletableDeferred<Unit>? = null

    /** Set, every foreground [loadPage] waits on it first: a load held out while the test acts. */
    var loadGate: CompletableDeferred<Unit>? = null

    /** Set, every [folders] waits on it first: the folder list's LIST held out while the test acts. */
    var foldersGate: CompletableDeferred<Unit>? = null

    /** How many times the folder list went out; one refused by its `stillWanted` is not counted. */
    var foldersSent = 0

    /**
     * The gates above stand for the wait behind the session; a request whose `stillWanted` says
     * no once its gate opens is not sent, as the real repository's is not, and ends the same way.
     */
    private fun unlessAbandoned(stillWanted: () -> Boolean) {
        if (!stillWanted()) throw MailError.Abandoned()
    }

    /** Every page request that went out, in order; one refused by its `stillWanted` is not here. */
    val loadsSent = mutableListOf<String>()

    /** Every search that went out, in order; one refused by its `stillWanted` is not here. */
    val searchesSent = mutableListOf<String>()

    /** Fires with the folder just after its page was produced and before it is returned: a test acts between two requests of one batch. */
    var onLoaded: ((String) -> Unit)? = null

    /** As [onLoaded], for a search. */
    var onSearched: ((String) -> Unit)? = null

    /** Thrown by [loadPage] for that folder alone, where [loadError] fails every folder. */
    val folderLoadErrors = mutableMapOf<String, MailError>()

    /** Thrown by [status] for that folder -- the poll's check of a folder on screen other than the inbox. */
    val folderStatusErrors = mutableMapOf<String, MailError>()
    val folderStatusCalls = mutableListOf<String>()
    var loadError: MailError? = null
    var searchUnsupported = false
    val bodies = mutableMapOf<Long, MailBody>()
    var bodyError: MailError? = null
    /**
     * Every `(folder, uid)` handed to [body], in order -- recorded before [bodyError] fires, so a
     * test can tell "the fetch was refused" apart from "the fetch was never attempted".
     */
    val bodyCalls = mutableListOf<Pair<String, Long>>()
    var moveError: MailError? = null
    var deleteError: MailError? = null
    /** Thrown by [setSeen] before it records anything, so a refused mark-read leaves no trace. */
    var seenError: MailError? = null
    val seenCalls = mutableListOf<Triple<String, Long, Boolean>>()
    val moved = mutableListOf<Triple<String, Long, String>>()
    val deleted = mutableListOf<Pair<String, Long>>()

    /**
     * Every folder name handed to any folder-taking call, in order. A merged view has no folder
     * of its own, so nothing here may ever be a name the server would not recognise -- this is
     * what a test asserts against to prove no synthetic name reaches an IMAP command.
     */
    val foldersTouched = mutableListOf<String>()
    /** Mirrors the real repository's cache: [loadPage]'s first page populates it, [dropCache] simulates a [MailError.FolderChanged] eviction. */
    private val cachedPages = mutableMapOf<String, MailPage>()
    /** Every `(folder, limit)` handed to [loadPage], in order -- what a prefetch test checks against. */
    val pageLimits = mutableListOf<Pair<String, Int>>()
    /**
     * Thrown only by a [loadPage] call marked `background` -- i.e. a
     * background prefetch, never the visible folder's own load.
     */
    var prefetchError: MailError? = null
    /**
     * While true, a background [loadPage] call suspends
     * instead of returning, so a test can assert what a cancel does to it mid-flight. Cancellable:
     * it is a plain suspending await, not a busy loop, so cancelling the caller's job ends it.
     */
    var blockPrefetch: Boolean = false
        set(value) {
            field = value
            if (!value) prefetchGate?.complete(Unit)
        }
    private var prefetchGate: CompletableDeferred<Unit>? = null
    /** How many [loadPage] calls are open right now -- incremented on entry, decremented in `finally`. */
    private var openLoads = 0
    /**
     * The largest [openLoads] ever observed. A sequential prefetch never lets a second
     * [loadPage] start before the one ahead of it returns, so this must stay 1 across a whole
     * prefetch run; a fan-out (e.g. `async` per folder) would let two calls sit inside
     * [loadPage] at once -- which, combined with [blockPrefetch] parking the first one open,
     * is exactly what this catches.
     */
    var maxConcurrentLoads = 0
        private set
    val sent = mutableListOf<Pair<OutgoingMail, Pair<String, Long>?>>()
    val drafts = mutableListOf<Pair<OutgoingMail, Long?>>()
    val discardedDrafts = mutableListOf<Long>()
    val sentAttachments = mutableListOf<List<Pair<String, String>>>()
    var sendError: MailError? = null
    /** What [send] reports about the sent copy; the default is the happy path. */
    var sentCopy: SentCopy = SentCopy.Filed
    /** Any [Throwable], not just [MailError] -- lets a test inject a [kotlinx.coroutines.CancellationException] too. */
    var discardDraftError: Throwable? = null
    var self = MailAddress("測試", "b10000001@mail.ntust.edu.tw")
    var raw = "Subject: x\r\n\r\nraw"
    var writeAttachmentError: MailError? = null
    /**
     * Written through [writeAttachment]'s own stream just before [writeAttachmentError] is thrown:
     * a download that fails part-way, having already put bytes where the caller pointed it -- what
     * an attachment refused at [org.ntust.app.tigerduck.mail.MailLimits.ATTACHMENT_BYTES] does.
     */
    var writeAttachmentPartial: String? = null
    /** Fires synchronously at the start of every [writeAttachment] call, before any error/write -- for tests that need to observe state mid-download. */
    var onWriteAttachment: (() -> Unit)? = null
    /** Fires synchronously at the start of every [body] call, before any error/return -- for tests that need to observe state while the body is still loading. */
    var onBody: (() -> Unit)? = null
    var released = 0
    var acquired = 0

    fun add(folder: String, vararg summaries: MailSummary) {
        mail.getOrPut(folder) { mutableListOf() } += summaries
    }

    private fun sorted(folder: String) = mail.getOrPut(folder) { mutableListOf() }.sortedByDescending { it.uid }

    private fun <T> touching(folder: String, block: () -> T): T {
        foldersTouched += folder
        return block()
    }

    private fun update(folder: String, uid: Long, transform: (MailSummary) -> MailSummary) {
        val list = mail[folder] ?: return
        val i = list.indexOfFirst { it.uid == uid }
        if (i >= 0) list[i] = transform(list[i])
    }

    override suspend fun folders(stillWanted: () -> Boolean): ResolvedFolders {
        foldersGate?.await()
        unlessAbandoned(stillWanted)
        foldersSent++
        return resolved
    }
    override suspend fun cachedPage(folder: String): MailPage? = touching(folder) { cachedPages[folder] }

    /** Simulates the repository dropping a folder's cache once [MailError.FolderChanged] fires for it. */
    fun dropCache(folder: String) {
        cachedPages.remove(folder)
    }

    override suspend fun loadPage(folder: String, beforeSeq: Int?, limit: Int, background: Boolean, stillWanted: () -> Boolean): MailPage {
        openLoads++
        maxConcurrentLoads = maxOf(maxConcurrentLoads, openLoads)
        try {
            if (!background) loadGate?.await()
            unlessAbandoned(stillWanted)
            foldersTouched += folder
            pageLimits += folder to limit
            loadsSent += folder
            if (background) {
                prefetchError?.let { throw it }
                if (blockPrefetch) {
                    val gate = CompletableDeferred<Unit>()
                    prefetchGate = gate
                    gate.await()
                }
            }
            loadError?.let { throw it }
            folderLoadErrors[folder]?.let { throw it }
            val all = sorted(folder)
            val from = beforeSeq ?: 0
            // `limit` is honoured, not just recorded: the real repository's rule about a warm
            // never shortening a cache only means anything if a warm can return a shorter page.
            val chunk = all.drop(from).take(minOf(pageSize, limit))
            val page = MailPage(1, all.size, chunk, (from + chunk.size).takeIf { it < all.size })
            // Mirrors MailRepository.loadPage: a first page replaces the cache, except for a
            // background warm that would leave less of the same mailbox behind than is there.
            if (beforeSeq == null) {
                val cached = cachedPages[folder]
                val shortens = background && cached != null &&
                    cached.uidValidity == page.uidValidity && cached.messages.size > page.messages.size
                if (!shortens) cachedPages[folder] = page
            }
            onLoaded?.invoke(folder)
            return page
        } finally {
            openLoads--
        }
    }

    override suspend fun inboxStatus(stillWanted: () -> Boolean): FolderStatus {
        statusGate?.await()
        unlessAbandoned(stillWanted)
        statusCalls++
        statusError?.let { throw it }
        return status
    }

    override suspend fun status(folder: String, stillWanted: () -> Boolean): FolderStatus {
        folderStatusGate?.await()
        unlessAbandoned(stillWanted)
        foldersTouched += folder
        folderStatusCalls += folder
        folderStatusErrors[folder]?.let { throw it }
        val newest = sorted(folder).firstOrNull()?.uid ?: 0L
        return FolderStatus(status.uidValidity, newest + 1, mail[folder]?.size ?: 0, 0)
    }
    override suspend fun refreshFlags(folder: String, uids: List<Long>): Map<Long, MailFlags> =
        touching(folder) { sorted(folder).filter { it.uid in uids }.associate { it.uid to it.flags } }
    override suspend fun summary(folder: String, uid: Long) = touching(folder) { sorted(folder).firstOrNull { it.uid == uid } }
    override suspend fun body(folder: String, uid: Long): MailBody {
        foldersTouched += folder
        bodyCalls += folder to uid
        onBody?.invoke()
        bodyError?.let { throw it }
        return bodies[uid] ?: throw MailError.Protocol("gone")
    }

    override suspend fun setSeen(folder: String, uid: Long, seen: Boolean) {
        seenError?.let { throw it }
        foldersTouched += folder
        seenCalls += Triple(folder, uid, seen)
        update(folder, uid) { it.copy(flags = it.flags.copy(seen = seen)) }
    }

    override suspend fun move(folder: String, uid: Long, target: String) {
        foldersTouched += folder
        foldersTouched += target
        moveError?.let { throw it }
        moved += Triple(folder, uid, target)
        mail[folder]?.removeAll { it.uid == uid }
    }

    /** Mirrors the real repository: permanent inside the trash, and permanent everywhere when the account has no trash folder and none could be created. */
    override suspend fun deletesPermanently(folder: String) =
        touching(folder) { resolved.nameOf(SpecialFolder.TRASH).let { it == null || it == folder } }

    override suspend fun delete(folder: String, uid: Long) {
        foldersTouched += folder
        deleteError?.let { throw it }
        deleted += folder to uid
        mail[folder]?.removeAll { it.uid == uid }
    }

    override suspend fun search(folder: String, query: String, stillWanted: () -> Boolean): SearchOutcome {
        unlessAbandoned(stillWanted)
        foldersTouched += folder
        searchesSent += folder
        val hits = sorted(folder).filter { query in it.subject }
        onSearched?.invoke(folder)
        return if (searchUnsupported) SearchOutcome.LoadedOnly(hits) else SearchOutcome.Server(hits)
    }

    var rawSourceError: MailError? = null
    var rawSourceCalls = 0

    override suspend fun rawSource(folder: String, uid: Long): String {
        foldersTouched += folder
        rawSourceCalls++
        rawSourceError?.let { throw it }
        return raw
    }

    override suspend fun writeAttachment(folder: String, uid: Long, partId: String, out: OutputStream) {
        foldersTouched += folder
        onWriteAttachment?.invoke()
        writeAttachmentError?.let { error ->
            writeAttachmentPartial?.let { out.write(it.toByteArray()) }
            throw error
        }
        out.write("attachment $partId".toByteArray())
    }

    override suspend fun send(mail: OutgoingMail, answered: Pair<String, Long>?): SentCopy {
        sendError?.let { throw it }
        sentAttachments += mail.attachments.map { it.fileName to it.open().use { s -> s.readBytes().decodeToString() } }
        sent += mail to answered
        return sentCopy
    }

    override suspend fun saveDraft(mail: OutgoingMail, replacingUid: Long?) {
        drafts += mail to replacingUid
    }

    override suspend fun discardDraft(uid: Long) {
        discardDraftError?.let { throw it }
        discardedDrafts += uid
    }

    override fun selfAddress() = self
    override fun release() {
        released++
    }

    override fun acquire() {
        acquired++
    }
}
