package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.moodle.MoodleAttachment
import org.ntust.app.tigerduck.data.moodle.MoodleCourseStore
import org.ntust.app.tigerduck.mail.resolveAttachmentMimeType
import org.ntust.app.tigerduck.data.moodle.MoodlePersonName
import org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussion
import org.ntust.app.tigerduck.network.model.moodle.MoodleFile
import org.ntust.app.tigerduck.network.moodle.MoodleHtmlText
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

/**
 * One announcement, laid out as a school bulletin is: author and time, title, the message, its
 * attachments, and a button to open it in Moodle. Its content came with the list, so it has
 * nothing to load. Opening it tells Moodle once that it was read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodleAnnouncementScreen(
    discussionId: Int,
    browserPreference: String,
    onBack: () -> Unit,
    onOpenRoute: (String) -> Unit,
    viewModel: MoodleCourseViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val store by viewModel.store.collectAsStateWithLifecycle()
    MoodleSignedOutEffect(viewModel, onBack)
    val news = store?.newsForum?.collectAsStateWithLifecycle()?.value
    val pages = remember(store, news?.id) { news?.let { store?.discussions(it) } }
    val list = pages?.list?.collectAsStateWithLifecycle()?.value
    val discussion = list?.discussions?.firstOrNull { it.id == discussionId }

    // Opened with nothing cached to show it from, as after the system ended the app.
    LaunchedEffect(store) { store?.ensureAnnouncement(discussionId) }

    // Coming back from a screen pushed over this one is not another opening.
    var opened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(discussion != null) {
        val current = store
        if (discussion != null && current != null && !opened) {
            opened = true
            current.open(discussion, news)
        }
    }

    Scaffold(
        topBar = { MoodleTopBar(title = "", onBack = onBack, moodlePath = null) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { openMoodlePage(context, MoodlePage.discussion(discussionId)) },
                icon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                text = { Text(stringResource(R.string.action_open_in_moodle)) },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val current = store
            if (discussion == null || current == null) {
                MoodleFirstLoad(failure = null, onRetry = {})
            } else {
                AnnouncementBody(current, discussion, browserPreference, onOpenRoute)
            }
        }
    }
}

@Composable
private fun AnnouncementBody(
    store: MoodleCourseStore,
    discussion: MoodleDiscussion,
    browserPreference: String,
    onOpenRoute: (String) -> Unit,
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AuthorRow(store, discussion, onOpenRoute)
        Text(
            text = discussion.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val markdown = remember(discussion.message) { MoodleHtmlText.markdown(discussion.message) }
        MoodleMarkdown(markdown, browserPreference, Modifier.fillMaxWidth())
        if (discussion.attachments.isNotEmpty()) {
            Attachments(store, discussion, browserPreference)
        }
    }
}

/** The author opens their member page where the course's members have them. */
@Composable
private fun AuthorRow(store: MoodleCourseStore, discussion: MoodleDiscussion, onOpenRoute: (String) -> Unit) {
    val members by store.members.state.collectAsStateWithLifecycle()
    val pictures by store.pictures.collectAsStateWithLifecycle()
    val member = remember(members, discussion.authorId) { store.member(discussion.authorId) }
    LaunchedEffect(member?.pictureUrl) { member?.let { store.loadPicture(it) } }
    val name = discussion.authorName?.let(MoodlePersonName::displayName).orEmpty()
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (name.isNotEmpty()) {
            MoodleAvatar(name, member?.pictureUrl?.let { pictures[it] }, 28.dp)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (member != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (member != null) {
                        Modifier.clickable(role = Role.Button) {
                            onOpenRoute(MoodleRoutes.member(store.courseId, member.id))
                        }
                    } else {
                        Modifier
                    },
                )
            }
            Spacer(Modifier.width(8.dp))
        } else {
            Spacer(Modifier.weight(1f))
        }
        Text(
            text = MoodleDates.dateTime(discussion.created),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
            maxLines = 1,
        )
    }
}

@Composable
private fun Attachments(store: MoodleCourseStore, discussion: MoodleDiscussion, browserPreference: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.school_mail_attachments),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column {
                discussion.attachments.forEachIndexed { index, file ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 56.dp),
                            color = cardDividerColor(),
                        )
                    }
                    AttachmentRow(store, file, discussion, browserPreference)
                }
            }
        }
    }
}

/**
 * A file the post carries. A tap downloads it into the app, with a spinner while it does, and
 * opens the copy in another app; a file off the Moodle site opens as its own link. A long press
 * offers "Open in Moodle".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AttachmentRow(store: MoodleCourseStore, file: MoodleFile, discussion: MoodleDiscussion, browserPreference: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloading by store.downloading.collectAsStateWithLifecycle()
    val busy = file.url in downloading
    var menu by remember { mutableStateOf(false) }
    val openInMoodle = stringResource(R.string.action_open_in_moodle)
    Box {
        ListItem(
            modifier = Modifier.combinedClickable(
                enabled = !busy,
                role = Role.Button,
                onLongClickLabel = openInMoodle,
                onLongClick = { menu = true },
                onClick = {
                    scope.launch {
                        when (val opened = store.openAttachment(file, discussion.postId)) {
                            is MoodleAttachment.Local -> runCatching {
                                context.startActivity(
                                    moodleFileIntent(
                                        context,
                                        opened.file,
                                        resolveAttachmentMimeType(file.mimeType.orEmpty(), file.name),
                                    ),
                                )
                            }
                            is MoodleAttachment.Link -> openMoodleContentLink(context, opened.url, browserPreference)
                            is MoodleAttachment.Failed -> Toast.makeText(
                                context,
                                moodleErrorText(context.resources, opened.error),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
            ),
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            leadingContent = {
                Icon(fileIcon(file), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            },
            headlineContent = { Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = if (file.size > 0) {
                { Text(Formatter.formatShortFileSize(context, file.size)) }
            } else {
                null
            },
            trailingContent = if (busy) {
                { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
            } else {
                null
            },
        )
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

private fun fileIcon(file: MoodleFile): ImageVector {
    val type = file.mimeType.orEmpty()
    val name = file.name.lowercase()
    return when {
        type == "application/pdf" || name.endsWith(".pdf") -> Icons.Outlined.PictureAsPdf
        type.startsWith("image/") -> Icons.Outlined.Image
        type.contains("zip") || type.contains("compressed") || name.endsWith(".zip") || name.endsWith(".rar") ->
            Icons.Outlined.FolderZip
        type.startsWith("text/") || type.contains("word") || type.contains("document") -> Icons.Outlined.Description
        else -> Icons.AutoMirrored.Outlined.InsertDriveFile
    }
}
