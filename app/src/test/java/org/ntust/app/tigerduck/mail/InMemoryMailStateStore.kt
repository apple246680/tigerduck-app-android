package org.ntust.app.tigerduck.mail

import org.ntust.app.tigerduck.mail.store.MailStateStore

class InMemoryMailStateStore : MailStateStore {
    override var displayName: String? = null
    override var notificationsEnabled: Boolean = true
    override var authFailed: Boolean = false
    override var demoMailbox: Boolean = false
    /** Fires on every [inboxUidValidity] read, after the value was read: lets a test act between the read and what follows it. */
    var onUidValidityRead: (() -> Unit)? = null
    override var inboxUidValidity: Long = 0
        get() = field.also { onUidValidityRead?.invoke() }
    override var inboxSeenUidNext: Long = 0
    override var lastForegroundCheckAt: Long = 0
    override var diagnostics: List<String> = emptyList()
        set(value) { field = value.take(10) }
    private val fetchedAt = mutableMapOf<String, Long>()
    private val owned = mutableMapOf<Pair<String, Long>, Set<Long>>()

    override fun fetchedAt(folder: String): Long = fetchedAt[folder] ?: 0L

    @Synchronized
    override fun setFetchedAtIf(folder: String, atMs: Long, stillCurrent: () -> Boolean): Boolean {
        if (!stillCurrent()) return false
        fetchedAt[folder] = atMs
        return true
    }

    /** Fires on every [ownedDeleted] read; with [beforeSetOwnedDeleted] it lets a test drive the
     *  exact interleaving of two overlapping owned-set read-modify-writes. */
    var onOwnedDeletedRead: (() -> Unit)? = null

    /** Fires just before a [setOwnedDeleted] write lands, with the set about to be stored. */
    var beforeSetOwnedDeleted: ((Set<Long>) -> Unit)? = null

    override fun ownedDeleted(folder: String, uidValidity: Long): Set<Long> {
        onOwnedDeletedRead?.invoke()
        return owned[folder to uidValidity].orEmpty()
    }

    override fun setOwnedDeleted(folder: String, uidValidity: Long, uids: Set<Long>) {
        beforeSetOwnedDeleted?.invoke(uids)
        owned.keys.removeAll { it.first == folder }
        owned[folder to uidValidity] = uids
    }

    @Synchronized
    override fun clear() {
        displayName = null; notificationsEnabled = true; authFailed = false; demoMailbox = false
        inboxUidValidity = 0; inboxSeenUidNext = 0; lastForegroundCheckAt = 0
        diagnostics = emptyList()
        fetchedAt.clear()
        owned.clear()
    }
}
