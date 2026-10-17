package org.ntust.app.tigerduck.ui.screen.announcements

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.data.BulletinReadStateStore
import org.ntust.app.tigerduck.data.BulletinRepository
import org.ntust.app.tigerduck.data.RefreshPolicy
import org.ntust.app.tigerduck.data.cache.BulletinCache
import org.ntust.app.tigerduck.data.preferences.CredentialManager
import org.ntust.app.tigerduck.demo.DemoAccount
import org.ntust.app.tigerduck.notification.SyncSource
import org.ntust.app.tigerduck.network.BulletinApiClient
import org.ntust.app.tigerduck.network.model.BulletinSummary
import org.ntust.app.tigerduck.network.model.TaxonomyResponse
import org.ntust.app.tigerduck.util.logSummary
import java.time.Instant
import javax.inject.Inject

/**
 * When the announcement list fetches on its own — see [RefreshPolicy]. The
 * default: on the first visit after launch only. Bulletins arrive a few a
 * day, and the ones a user subscribes to are pushed.
 */
internal val AnnouncementsRefreshPolicy = RefreshPolicy()

/**
 * Drives AnnouncementsScreen. Ports BulletinsViewModel.swift faithfully:
 * disk-cache seed → network refresh → background prefetch chain → filter
 * intersection. Sort key is `(postedAt DESC, id DESC)` so server pages and
 * cached items merge cleanly without duplicates or pinned-post jumps.
 */
@HiltViewModel
class AnnouncementsViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val api: BulletinApiClient,
    private val cache: BulletinCache,
    private val repository: BulletinRepository,
    private val readState: BulletinReadStateStore,
    private val demoAccount: DemoAccount,
    private val credentials: CredentialManager,
) : ViewModel() {


    sealed interface LoadState {
        data object Idle : LoadState
        data object Loading : LoadState
        data object Loaded : LoadState
        data class Failed(val message: String) : LoadState
    }

    data class State(
        val items: List<BulletinSummary> = emptyList(),
        val filtered: List<BulletinSummary> = emptyList(),
        val loadState: LoadState = LoadState.Idle,
        val isPaginating: Boolean = false,
        val hasMore: Boolean = true,
        val taxonomy: TaxonomyResponse? = null,
        val selectedOrgs: Set<String> = emptySet(),
        val selectedTags: Set<String> = emptySet(),
        val searchText: String = "",
        val showDeleted: Boolean = false,
        val unreadOnly: Boolean = false,
        val readIds: Set<Int> = emptySet(),
        /**
         * [filtered] minus already-read items when [unreadOnly] is on. Stored
         * (not computed) so Compose recompositions triggered by frequent
         * readIds ticks don't re-run the filter pass on every read.
         */
        val displayed: List<BulletinSummary> = emptyList(),
        /** For the sync dot's "Last synced" row; see [BulletinCache.loadFetchedAt]. */
        val syncedAtMs: Long = 0L,
    ) {
        val hasUnread: Boolean
            get() = filtered.any { it.id !in readIds }
    }

    private val _state = MutableStateFlow(
        // A return visit finds the department list in memory, so the chips are
        // there from the first frame, with whatever this entry saved; a first
        // visit waits for fetchTaxonomyOnce(). A new entry has nothing saved,
        // so its filters start empty.
        repository.taxonomy().let {
            State(taxonomy = it, selectedOrgs = savedOrgs(it), selectedTags = savedTags(it))
        }
    )
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        readState.readIds
            .onEach { ids -> _state.update { withDisplayed(it.copy(readIds = ids)) } }
            .launchIn(viewModelScope)
    }

    // Main-thread-only: read/written from coroutines launched on
    // viewModelScope, which inherits Dispatchers.Main.immediate. Do not
    // touch from a non-Main dispatcher without adding synchronisation.
    private var nextCursor: Int? = null
    private var inflight: Job? = null
    private var prefetch: Job? = null
    private var loadMoreJob: Job? = null

    // Main-thread-only — see nextCursor.
    private var hasLoaded = false

    fun load() {
        if (hasLoaded) return
        hasLoaded = true
        viewModelScope.launch {
            val cached = cache.load()
            val fetchedAt = cache.loadFetchedAt()
            _state.update { it.copy(syncedAtMs = fetchedAt) }
            if (cached.isNotEmpty()) {
                repository.putSummaries(cached)
                _state.update { applyFilters(it.copy(items = sortedUnique(cached))) }
            }
            // This view model is rebuilt on every visit, so each visit used
            // to refetch the first page and prefetch five more behind it,
            // only to replace the cached list with the same list. A return
            // the policy does not fetch for keeps what the last refresh
            // saved, and picks the cursor up where it stopped.
            val session = repository.listSession(_state.value.showDeleted)
            val policy = AnnouncementsRefreshPolicy
            val fetch = when {
                // Nothing to show: there is no list to keep.
                cached.isEmpty() -> true
                // The first visit since the app started.
                session == null -> policy.onLaunch
                else -> policy.onRevisit && RefreshPolicy.hasPassed(
                    session.fetchedAtMs, SystemClock.elapsedRealtime(), policy.minInterval,
                )
            }
            if (fetch) {
                refresh()
            } else {
                // Without a session (a policy that skips the launch) there is
                // no cursor either, so the cached list does not page on until
                // a pull.
                nextCursor = session?.nextCursor
                _state.update {
                    it.copy(loadState = LoadState.Loaded, hasMore = session?.nextCursor != null)
                }
            }
            launch {
                try {
                    fetchTaxonomyOnce()
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Taxonomy is decorative (org/tag labels); the list works
                    // without it. Still log — a permanently failing taxonomy
                    // endpoint would otherwise be invisible.
                    android.util.Log.w("AnnouncementsVM", "Taxonomy fetch failed: ${e.logSummary()}")
                }
            }
        }
    }

    private suspend fun fetchTaxonomyOnce() {
        if (_state.value.taxonomy != null) return
        val tax = repository.getOrFetchTaxonomy {
            runCatching { api.fetchTaxonomy() }.getOrNull()
        } ?: return
        _state.update {
            applyFilters(it.copy(taxonomy = tax, selectedOrgs = savedOrgs(tax), selectedTags = savedTags(tax)))
        }
    }

    /**
     * The departments this back-stack entry has selected, from [savedState]: a
     * selection belongs to the visit that made it, and when Android kills the
     * process while a bulletin is open, Back arrives in a recreated view model
     * that would otherwise start empty. None until the department list has
     * loaded, and only those it still lists — a filter with no chip on screen
     * to show it or turn it off is what this keeps out. A new entry has nothing
     * saved; its filters start empty, as the Remember bulletin filters option
     * once stopped doing (#376).
     */
    private fun savedOrgs(taxonomy: TaxonomyResponse?): Set<String> =
        savedIds(KEY_SELECTED_ORGS, taxonomy?.orgs?.map { it.id })

    /** The categories this entry has selected; see [savedOrgs]. */
    private fun savedTags(taxonomy: TaxonomyResponse?): Set<String> =
        savedIds(KEY_SELECTED_TAGS, taxonomy?.tags?.map { it.id })

    private fun savedIds(key: String, known: List<String>?): Set<String> {
        if (known == null) return emptySet()
        val saved = savedState.get<Array<String>>(key) ?: return emptySet()
        return saved.toSet() intersect known.toSet()
    }

    fun refresh() {
        // The demo account's bulletins are the ones its sign-in cached, and
        // DemoModeInterceptor refuses the fetch: the cached list is the loaded
        // one, with no page behind it, rather than a refresh that can only fail.
        if (demoAccount.isActive) {
            _state.update { it.copy(loadState = LoadState.Loaded, hasMore = false) }
            return
        }
        inflight?.cancel()
        prefetch?.cancel()
        loadMoreJob?.cancel()
        nextCursor = null
        inflight = viewModelScope.launch {
            // Capture before any suspend so a concurrent toggle of showDeleted
            // can't desync the fetch filter from the UI state.
            val includeDeleted = _state.value.showDeleted
            _state.update { it.copy(loadState = LoadState.Loading, isPaginating = false) }
            // The school account as it is now; a page that arrives for another is dropped.
            val account = credentials.schoolAccountGeneration
            try {
                val response = api.fetchList(
                    cursor = null,
                    includeDeleted = includeDeleted,
                )
                // Defend against a cancelled launch resuming past the network
                // call: writing nextCursor / items here would overwrite the
                // newer refresh that triggered our cancellation.
                coroutineContext.ensureActive()
                if (droppedAfterSignOut(account)) return@launch
                // Wall clock, for the "Last synced" row and across restarts;
                // the session's elapsedRealtime below is neither.
                val fetchedAt = System.currentTimeMillis()
                val merged = sortedUnique(_state.value.items + response.items)
                nextCursor = response.nextCursor
                repository.putSummaries(merged)
                _state.update {
                    applyFilters(
                        it.copy(
                            items = merged,
                            loadState = LoadState.Loaded,
                            hasMore = response.nextCursor != null,
                            syncedAtMs = fetchedAt,
                        )
                    )
                }
                if (!saveUnlessSignedOut(merged, fetchedAt, account)) return@launch
                repository.listRefreshed(
                    SystemClock.elapsedRealtime(), includeDeleted, response.nextCursor,
                )
                // Only prune when the cursor chain is exhausted — pruning on the
                // first page would drop read-IDs for older bulletins not yet
                // fetched (e.g., after Auto Backup restore on reinstall).
                if (response.nextCursor == null) {
                    val ids = merged.map { it.id }
                    readState.prune(ids)
                    cache.pruneDetails(ids.toSet())
                } else {
                    startBackgroundPrefetch(includeDeleted)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Allow load() to retry on next screen entry if the initial
                // fetch failed and there's no cached data to fall back on.
                if (_state.value.items.isEmpty()) {
                    hasLoaded = false
                }
                _state.update { it.copy(loadState = LoadState.Failed(e.message ?: "error")) }
            }
        }
    }

    // refresh() cancels prefetch before relaunching, but cancellation of a
    // coroutine suspended inside withContext(Dispatchers.IO) (the OkHttp call)
    // is deferred until the network returns. In that window prefetch and the
    // new inflight can both resume on Main and race on nextCursor / state.
    // Dispatchers.Main.immediate makes overlap vanishingly rare today, but the
    // ensureActive() before each write is what actually keeps the older job
    // from clobbering newer state — don't remove it on the assumption that
    // Main scheduling alone is enough.
    private fun startBackgroundPrefetch(includeDeleted: Boolean) {
        prefetch?.cancel()
        prefetch = viewModelScope.launch {
            // Walk the cursor chain in the background so the user doesn't see
            // a load spinner mid-scroll. Cap at 5 pages — anything older is
            // legitimately a "load more" tap.
            var pages = 0
            var latest: List<BulletinSummary>? = null
            val account = credentials.schoolAccountGeneration
            while (pages < 5 && nextCursor != null) {
                pages++
                delay(150)
                val cursor = nextCursor ?: break
                val response = try {
                    api.fetchList(cursor = cursor, includeDeleted = includeDeleted)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    break
                }
                coroutineContext.ensureActive()
                if (droppedAfterSignOut(account)) return@launch
                val merged = sortedUnique(_state.value.items + response.items)
                nextCursor = response.nextCursor
                repository.putSummaries(merged)
                _state.update {
                    applyFilters(it.copy(items = merged, hasMore = response.nextCursor != null))
                }
                latest = merged
                if (response.nextCursor == null) {
                    val ids = merged.map { it.id }
                    readState.prune(ids)
                    cache.pruneDetails(ids.toSet())
                    break
                }
            }
            // Persist the final merged snapshot once when the prefetch chain
            // settles, instead of rewriting summaries.json on every page.
            latest?.let {
                if (saveUnlessSignedOut(it, null, account)) repository.listAdvanced(includeDeleted, nextCursor)
            }
        }
    }

    /**
     * Asks for the next page once the list has been scrolled within five bulletins of the end.
     *
     * Takes the id rather than the bulletin, because the id is what the caller can be sure of:
     * it is the list item's key, and so survives anything the screen puts above the bulletins.
     */
    fun loadMoreIfNeeded(lastVisibleId: Int) {
        val s = _state.value
        if (!s.hasMore || s.isPaginating) return
        val tail = s.displayed.takeLast(5).map { it.id }
        if (lastVisibleId !in tail) return
        val cursor = nextCursor ?: return
        prefetch?.cancel()
        _state.update { it.copy(isPaginating = true) }
        loadMoreJob = viewModelScope.launch {
            val account = credentials.schoolAccountGeneration
            try {
                val response = api.fetchList(cursor = cursor, includeDeleted = s.showDeleted)
                coroutineContext.ensureActive()
                if (droppedAfterSignOut(account)) return@launch
                val merged = sortedUnique(_state.value.items + response.items)
                nextCursor = response.nextCursor
                repository.putSummaries(merged)
                _state.update {
                    applyFilters(
                        it.copy(
                            items = merged,
                            isPaginating = false,
                            hasMore = response.nextCursor != null,
                        )
                    )
                }
                if (!saveUnlessSignedOut(merged, null, account)) return@launch
                repository.listAdvanced(s.showDeleted, response.nextCursor)
                if (response.nextCursor == null) {
                    val ids = merged.map { it.id }
                    readState.prune(ids)
                    cache.pruneDetails(ids.toSet())
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(isPaginating = false) }
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(isPaginating = false) }
            }
        }
    }

    /**
     * Whether a school sign-out has come since [account] was read from
     * [CredentialManager.schoolAccountGeneration], before a fetch. The sign-out clears the cached
     * list and its date ([org.ntust.app.tigerduck.auth.AuthService.logout]); a page that arrives
     * after it would write them back, and the departing student's list would be the next one's
     * first paint. Bulletins are public, but the sign-out is still where the list is let go of.
     * So the page is dropped and the list starts over: the next visit reads the cleared cache,
     * then the feed, as on the iPhone. The write to the cache that follows a page let through
     * asks again, under the cache's lock; see [saveUnlessSignedOut].
     */
    private fun droppedAfterSignOut(account: Int): Boolean {
        if (credentials.isSchoolAccount(account)) return false
        startOver()
        return true
    }

    /**
     * Writes the list, and its date when given, unless a sign-out has come since [account] was
     * read -- asked under the cache's lock, which the sign-out's clear takes too, so the check
     * [droppedAfterSignOut] made a moment earlier cannot be overtaken between it and the write.
     * Refused, the list starts over as there.
     */
    private suspend fun saveUnlessSignedOut(items: List<BulletinSummary>, fetchedAt: Long?, account: Int): Boolean {
        if (cache.saveIf(items, fetchedAt) { credentials.isSchoolAccount(account) }) return true
        startOver()
        return false
    }

    /** Back to before the first visit: nothing in memory, and [load] seeds from the cache again. */
    private fun startOver() {
        prefetch?.cancel()
        loadMoreJob?.cancel()
        nextCursor = null
        hasLoaded = false
        _state.update {
            applyFilters(
                it.copy(items = emptyList(), loadState = LoadState.Idle, isPaginating = false, hasMore = true, syncedAtMs = 0L)
            )
        }
    }

    fun setOrgFilter(orgs: Set<String>) {
        savedState[KEY_SELECTED_ORGS] = orgs.toTypedArray()
        _state.update { applyFilters(it.copy(selectedOrgs = orgs)) }
    }

    fun setTagFilter(tags: Set<String>) {
        savedState[KEY_SELECTED_TAGS] = tags.toTypedArray()
        _state.update { applyFilters(it.copy(selectedTags = tags)) }
    }

    fun setSearch(text: String) =
        _state.update { applyFilters(it.copy(searchText = text)) }

    fun setUnreadOnly(value: Boolean) =
        _state.update { withDisplayed(it.copy(unreadOnly = value)) }

    fun setShowDeleted(value: Boolean) {
        if (_state.value.showDeleted == value) return
        _state.update { applyFilters(it.copy(showDeleted = value)) }
        refresh()
    }

    fun toggleRead(id: Int) = readState.toggleRead(id)

    fun markAllRead() {
        val visibleIds = _state.value.filtered.map { it.id }
        readState.markAllRead(visibleIds)
    }

    private fun applyFilters(s: State): State {
        val text = s.searchText.trim()
        val filtered = s.items.asSequence()
            .filter { s.showDeleted || !it.isDeleted }
            .filter { s.selectedOrgs.isEmpty() || it.canonicalOrg in s.selectedOrgs }
            .filter {
                s.selectedTags.isEmpty() || it.contentTags.any { tag -> tag in s.selectedTags }
            }
            .filter {
                if (text.isEmpty()) true
                else it.displayTitle.contains(text, ignoreCase = true) ||
                        it.summary?.contains(text, ignoreCase = true) == true
            }
            .toList()
        return withDisplayed(s.copy(filtered = filtered))
    }

    private fun withDisplayed(s: State): State {
        val displayed = if (s.unreadOnly) s.filtered.filter { it.id !in s.readIds } else s.filtered
        return s.copy(displayed = displayed)
    }

    private fun sortedUnique(items: List<BulletinSummary>): List<BulletinSummary> {
        val byId = LinkedHashMap<Int, BulletinSummary>(items.size)
        // Later entries win — server fetch overwrites stale cache row for
        // the same id (e.g. importance/title_clean updates after LLM run).
        for (item in items) byId[item.id] = item
        return byId.values.sortedWith(
            compareByDescending<BulletinSummary> {
                it.postedAt?.let(::parseInstant) ?: Instant.EPOCH
            }
                .thenByDescending { it.id }
        )
    }

    private fun parseInstant(raw: String): Instant =
        try {
            Instant.parse(raw)
        } catch (_: Exception) {
            Instant.EPOCH
        }

    private companion object {
        /** [SavedStateHandle] keys: the ids selected on this back-stack entry, as string arrays. */
        const val KEY_SELECTED_ORGS = "selectedOrgs"
        const val KEY_SELECTED_TAGS = "selectedTags"
    }
}
