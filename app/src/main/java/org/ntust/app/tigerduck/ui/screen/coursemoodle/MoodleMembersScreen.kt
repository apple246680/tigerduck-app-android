package org.ntust.app.tigerduck.ui.screen.coursemoodle

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.moodle.MoodleCourseStore
import org.ntust.app.tigerduck.data.moodle.MoodleMemberPlacement
import org.ntust.app.tigerduck.data.moodle.MoodleMemberSection
import org.ntust.app.tigerduck.data.moodle.MoodleMembers
import org.ntust.app.tigerduck.data.moodle.name
import org.ntust.app.tigerduck.data.moodle.studentId
import org.ntust.app.tigerduck.network.model.moodle.MoodleMember
import org.ntust.app.tigerduck.ui.component.EmptyStateView
import org.ntust.app.tigerduck.ui.component.searchClearIcon
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import java.text.NumberFormat

/**
 * The people of a course the account can see, in sections by role. The search is a row of the
 * list above its top, which a pull on the list brings down, as on iOS; it matches on the device
 * and asks Moodle nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodleMembersScreen(
    onBack: () -> Unit,
    onOpenRoute: (String) -> Unit,
    viewModel: MoodleCourseViewModel = hiltViewModel(),
) {
    val store by viewModel.store.collectAsStateWithLifecycle()
    MoodleSignedOutEffect(viewModel, onBack)
    var query by rememberSaveable { mutableStateOf("") }
    val current = store
    Scaffold(
        topBar = {
            MoodleTopBar(
                title = stringResource(R.string.course_detail_members_label),
                onBack = onBack,
                moodlePath = current?.let { MoodlePage.participants(it.courseId) },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (current == null) {
                MoodleFirstLoad(null, {})
            } else {
                MembersList(
                    store = current,
                    query = query,
                    onQueryChange = { query = it },
                    onOpen = { onOpenRoute(MoodleRoutes.member(current.courseId, it.id)) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MembersList(
    store: MoodleCourseStore,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpen: (MoodleMember) -> Unit,
) {
    val state by store.members.state.collectAsStateWithLifecycle()
    val pictures by store.pictures.collectAsStateWithLifecycle()
    MoodleAutoRefresh(store, store.members.ttlMs, store::awaitCache, { store.members.state.value.fetchedAtMs }) {
        store.refreshMembers()
    }
    var pulling by remember { mutableStateOf(false) }
    val refresher = rememberRefresher { store.refreshMembers() }
    val scope = rememberCoroutineScope()
    val list = state.value
    val sections = remember(list, query) { list?.let { MoodleMembers.sections(it.members, query) }.orEmpty() }

    // The search row is the list's first item and the list opens on its second, so the row waits
    // above the top: a short pull scrolls it into view, a longer one goes on to the refresh, and
    // the next scroll up puts it away -- iOS's pull-to-reveal, as Material's list has it. Opening
    // on the second item holds only while there is one: a first fetch composes the spinner alone,
    // which puts the position back on the first, so the row is put away once the list arrives.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = 1)
    var searchPutAway by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(list != null) {
        if (list != null && !searchPutAway) {
            searchPutAway = true
            listState.scrollToItem(1)
        }
    }

    PullToRefreshBox(
        isRefreshing = pulling,
        onRefresh = { pulling = true; refresher { pulling = false } },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = MembersListVerticalPadding),
        ) {
            if (list == null) {
                item {
                    MoodleFirstLoad(
                        failure = if (state.isRefreshing) null else state.failure,
                        onRetry = { scope.launch { store.refreshMembers() } },
                    )
                }
                return@LazyColumn
            }
            // Before everything the query decides, so a query that empties the list keeps the
            // field, and the keyboard with it.
            item(key = "search") { SearchRow(query, onQueryChange) }
            state.failureBesideCopy?.let { failure ->
                item(key = "failure") {
                    MoodleRefreshFailure(
                        failure,
                        onRetry = { scope.launch { store.refreshMembers() } },
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }
            if (sections.isEmpty() && query.isNotBlank()) {
                item(key = "none") {
                    EmptyStateView(
                        icon = Icons.Filled.SearchOff,
                        title = stringResource(R.string.desktop_bulletins_no_matches),
                        message = null,
                    )
                }
            }
            // One item per row, so a large class draws only the rows on screen, and only they
            // fetch their pictures.
            sections.forEach { section ->
                item(key = "section-${section.placement}") { MemberSectionHeader(section) }
                itemsIndexed(section.members, key = { _, member -> "member-${member.id}" }) { index, member ->
                    MemberCardRow(
                        store = store,
                        member = member,
                        picture = member.pictureUrl?.let { pictures[it] },
                        first = index == 0,
                        last = index == section.members.lastIndex,
                        onOpen = onOpen,
                    )
                }
            }
        }
    }
}

/** The list's padding above its first item and below its last, and the search row's below the field. */
private val MembersListVerticalPadding = 12.dp

/**
 * The search row. It asks for no focus of its own: it comes into view by a scroll, and a keyboard
 * rising on every pull would cover the list the pull was for.
 */
@Composable
private fun SearchRow(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.action_search)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = searchClearIcon(query) { onQueryChange("") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = RoundedCornerShape(28.dp),
        // As much room below the field as the list keeps above its first item: the list draws
        // under that padding, and with less the field's bottom edge would show through it at rest.
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = MembersListVerticalPadding),
    )
}

@Composable
private fun MemberSectionHeader(section: MoodleMemberSection) {
    val title = placementTitle(section.placement)
    val count = remember(section.members.size) { NumberFormat.getIntegerInstance().format(section.members.size) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 8.dp)
            .clearAndSetSemantics {
                heading()
                contentDescription = title
                stateDescription = count
            },
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = count,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
        )
    }
}

/** A row of a section's card: the card's corners round at its first and last row. */
@Composable
private fun MemberCardRow(
    store: MoodleCourseStore,
    member: MoodleMember,
    picture: androidx.compose.ui.graphics.ImageBitmap?,
    first: Boolean,
    last: Boolean,
    onOpen: (MoodleMember) -> Unit,
) {
    LaunchedEffect(member.pictureUrl) { store.loadPicture(member) }
    val corner = 16.dp
    val shape = RoundedCornerShape(
        topStart = if (first) corner else 0.dp,
        topEnd = if (first) corner else 0.dp,
        bottomStart = if (last) corner else 0.dp,
        bottomEnd = if (last) corner else 0.dp,
    )
    Surface(shape = shape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column {
            if (!first) {
                HorizontalDivider(modifier = Modifier.padding(start = 72.dp), color = cardDividerColor())
            }
            MemberRow(member, picture) { onOpen(member) }
        }
    }
}

/** A person in the list: picture, name, and student ID and department. */
@Composable
private fun MemberRow(member: MoodleMember, picture: androidx.compose.ui.graphics.ImageBitmap?, onClick: () -> Unit) {
    val subline = listOfNotNull(member.studentId, member.department).joinToString(" · ")
    ListItem(
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = member.name
                if (subline.isNotEmpty()) stateDescription = subline
            },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        leadingContent = { MoodleAvatar(member.name, picture, 40.dp) },
        headlineContent = { Text(member.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (subline.isNotEmpty()) {
            { Text(subline, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } else {
            null
        },
        trailingContent = {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.DISABLED),
            )
        },
    )
}

@Composable
internal fun placementTitle(placement: MoodleMemberPlacement): String = when (placement) {
    MoodleMemberPlacement.Instructors -> stringResource(R.string.course_detail_instructor_label)
    MoodleMemberPlacement.Assistants -> stringResource(R.string.course_members_tas)
    is MoodleMemberPlacement.Other -> placement.name
    MoodleMemberPlacement.Auditors -> stringResource(R.string.course_members_auditors)
    MoodleMemberPlacement.Students -> stringResource(R.string.course_members_students)
}
