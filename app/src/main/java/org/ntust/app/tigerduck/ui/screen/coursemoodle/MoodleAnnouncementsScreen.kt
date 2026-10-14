package org.ntust.app.tigerduck.ui.screen.coursemoodle

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.moodle.MoodleCourseStore
import org.ntust.app.tigerduck.data.moodle.MoodleDiscussionPages
import org.ntust.app.tigerduck.data.moodle.MoodleDiscussions
import org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussion
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

/**
 * A course's announcements, from its news forum on Moodle: pinned ones first, then newest first,
 * a page of twenty at a time. Loading the list marks nothing read; only opening one does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodleAnnouncementsScreen(
    onBack: () -> Unit,
    onOpenRoute: (String) -> Unit,
    viewModel: MoodleCourseViewModel = hiltViewModel(),
) {
    val store by viewModel.store.collectAsStateWithLifecycle()
    MoodleSignedOutEffect(viewModel, onBack)
    val title = stringResource(R.string.feature_announcements)
    val news = store?.newsForum?.collectAsStateWithLifecycle()?.value
    Scaffold(
        topBar = { MoodleTopBar(title, onBack, news?.let { MoodlePage.forum(it.cmid) }) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val current = store
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                current == null -> MoodleFirstLoad(failure = null, onRetry = {})
                news == null -> ForumsFirstLoad(current)
                else -> DiscussionList(
                    pages = remember(current, news.id) { current.discussions(news) },
                    onOpen = { onOpenRoute(MoodleRoutes.announcement(current.courseId, it.id)) },
                )
            }
        }
    }
}

/** Before the course's news forum is known: the forums' first fetch, its error and retry. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForumsFirstLoad(store: MoodleCourseStore) {
    val state by store.forums.state.collectAsStateWithLifecycle()
    MoodleAutoRefresh(store, store.forums.ttlMs, store::awaitCache, { store.forumsFetchedAtMs }) { store.refreshForums() }
    var pulling by remember { mutableStateOf(false) }
    val refresher = rememberRefresher { store.refreshForums() }
    val scope = rememberCoroutineScope()
    PullToRefreshBox(
        isRefreshing = pulling,
        onRefresh = { pulling = true; refresher { pulling = false } },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                MoodleFirstLoad(
                    failure = if (state.isRefreshing) null else state.failure,
                    onRetry = { scope.launch { store.refreshForums() } },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscussionList(
    pages: MoodleDiscussionPages,
    onOpen: (MoodleDiscussion) -> Unit,
) {
    val list by pages.list.collectAsStateWithLifecycle()
    val feed by pages.feed.state.collectAsStateWithLifecycle()
    val loadingMore by pages.isLoadingMore.collectAsStateWithLifecycle()
    val pageFailure by pages.pageFailure.collectAsStateWithLifecycle()
    MoodleAutoRefresh(pages, pages.feed.ttlMs, { pages.feed.awaitCache() }, { pages.fetchedAtMs }) { pages.refresh() }

    var pulling by remember { mutableStateOf(false) }
    val refresher = rememberRefresher { pages.refresh() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val discussions = remember(list) { list?.let { MoodleDiscussions.sorted(it, pages.byModified) }.orEmpty() }

    // The next page once the list's end comes on screen.
    LaunchedEffect(listState, pages) {
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.key
            last
        }
            .distinctUntilChanged()
            .filter { it != null && it == discussions.lastOrNull()?.id }
            .collect { pages.loadMore(it as Int) }
    }

    PullToRefreshBox(
        isRefreshing = pulling,
        onRefresh = { pulling = true; refresher { pulling = false } },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (list == null) {
                item {
                    MoodleFirstLoad(
                        failure = if (feed.isRefreshing) null else feed.failure,
                        onRetry = { scope.launch { pages.refresh() } },
                    )
                }
            } else {
                items(discussions, key = { it.id }) { discussion ->
                    MoodleDiscussionCard(
                        discussion = discussion,
                        dateSeconds = if (pages.byModified) discussion.modified else discussion.created,
                        onClick = { onOpen(discussion) },
                    )
                }
                if (loadingMore) {
                    item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                } else if (pageFailure != null && list?.hasMore == true) {
                    item(key = "retry") {
                        PageRetry(pageFailure!!) { scope.launch { pages.loadMore(discussions.lastOrNull()?.id) } }
                    }
                }
            }
        }
    }
}

/** The list's end when its next page failed, so a partial list does not pass for the whole. */
@Composable
private fun PageRetry(failure: Throwable, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = moodleErrorMessage(failure),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
            textAlign = TextAlign.Center,
        )
        OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}

/**
 * A discussion in a list: unread dot and bold title, pin, paperclip and time, then two lines of
 * its message. A long press offers "Open in Moodle".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MoodleDiscussionCard(discussion: MoodleDiscussion, dateSeconds: Long, onClick: () -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val unread = discussion.unreadCount > 0
    val unreadLabel = stringResource(R.string.bulletin_unread_dot_label)
    val pinnedLabel = stringResource(R.string.course_forum_pinned)
    val attachmentsLabel = stringResource(R.string.school_mail_attachments)
    val openInMoodle = stringResource(R.string.action_open_in_moodle)
    val date = remember(dateSeconds) { MoodleDates.listDate(dateSeconds, System.currentTimeMillis()) }
    val spoken = remember(discussion, date) {
        listOfNotNull(
            unreadLabel.takeIf { unread },
            pinnedLabel.takeIf { discussion.isPinned },
            discussion.title,
            MoodleDates.dateTime(dateSeconds),
            discussion.preview.takeIf { it.isNotEmpty() },
            attachmentsLabel.takeIf { discussion.attachments.isNotEmpty() },
        ).joinToString(", ")
    }
    Box {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    role = Role.Button,
                    onLongClickLabel = openInMoodle,
                    onLongClick = { menu = true },
                    onClick = onClick,
                )
                .clearAndSetSemantics { contentDescription = spoken },
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    if (unread) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 7.dp, end = 6.dp),
                        )
                    }
                    if (discussion.isPinned) {
                        Icon(
                            Icons.Filled.PushPin,
                            contentDescription = null,
                            tint = MoodleOrange,
                            modifier = Modifier
                                .padding(top = 2.dp, end = 4.dp)
                                .size(16.dp),
                        )
                    }
                    Text(
                        text = discussion.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    if (discussion.attachments.isNotEmpty()) {
                        Icon(
                            Icons.Filled.AttachFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                            modifier = Modifier
                                .padding(top = 2.dp, end = 2.dp)
                                .size(16.dp),
                        )
                    }
                    Text(
                        text = date,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                        maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (discussion.preview.isNotEmpty()) {
                    Text(
                        text = discussion.preview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(openInMoodle) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                onClick = {
                    menu = false
                    openMoodlePage(context, MoodlePage.discussion(discussion.id))
                },
            )
        }
    }
}
