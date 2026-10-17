package org.ntust.app.tigerduck.ui.screen.coursemoodle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.moodle.MoodleAccountAssignments
import org.ntust.app.tigerduck.data.moodle.MoodleAssignmentEntry
import org.ntust.app.tigerduck.data.moodle.MoodleAssignmentLookup
import org.ntust.app.tigerduck.data.moodle.MoodleCourseStore
import org.ntust.app.tigerduck.data.moodle.MoodleFeed
import org.ntust.app.tigerduck.data.moodle.MoodlePersonName
import org.ntust.app.tigerduck.network.model.moodle.MoodleAssignmentSubmission
import org.ntust.app.tigerduck.network.model.moodle.MoodleSubmissionComment
import org.ntust.app.tigerduck.network.moodle.MoodleWebservice
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

/** What the page shows of an assignment, apart from what is on screen. */
internal object AssignmentPageRules {
    /**
     * The assignment's page on the Moodle site: by its course module, else by the link the round
     * gave Home's row; null without either, for a row from a cache written before the link was.
     */
    fun moodlePath(entry: MoodleAssignmentEntry): String? {
        if (entry.record.cmid > 0) return MoodlePage.assignment(entry.record.cmid)
        val url = entry.listed?.moodleUrl?.takeIf { it.startsWith(MoodleWebservice.SITE + "/") } ?: return null
        return url.removePrefix(MoodleWebservice.SITE)
    }

    /**
     * Where the bottom button opens the assignment for submitting; null, and no button, for an
     * assignment that takes no online submission or has no page to open.
     */
    fun submitPath(entry: MoodleAssignmentEntry): String? =
        if (entry.record.noSubmissions) null else moodlePath(entry)

    /** The due time the page shows, Unix seconds: the record's, else Home's row's; null for an undated one. */
    fun dueSeconds(entry: MoodleAssignmentEntry): Long? =
        entry.record.dueDate.takeIf { it > 0 } ?: entry.listed?.dueDate?.let { it.time / 1000 }

    /**
     * The submission's time, Unix seconds, and before the first fetch answers, the one the
     * assignment round found; null for anything not handed in, a draft included.
     */
    fun submittedSeconds(entry: MoodleAssignmentEntry, submission: MoodleAssignmentSubmission?): Long? {
        if (submission != null) return submission.submittedAt
        val listed = entry.listed ?: return null
        return if (listed.isCompleted) listed.submittedAt?.let { it.time / 1000 } else null
    }

    /**
     * The course's name for the page's header: Home's row's, else that of any row of the same
     * course, for an assignment Home leaves out; empty while no row names it.
     */
    fun courseName(entry: MoodleAssignmentEntry, assignments: MoodleAccountAssignments?): String {
        entry.listed?.courseName?.takeIf { it.isNotEmpty() }?.let { return it }
        val courseId = entry.record.courseId.takeIf { it > 0 } ?: return ""
        val siblings = assignments?.records?.value.orEmpty().filter { it.courseId == courseId }.map { it.assignId.toString() }.toSet()
        return assignments?.listed?.value.orEmpty().firstOrNull { it.assignmentId in siblings }?.courseName.orEmpty()
    }
}

/**
 * One assignment, laid out as an announcement is: its course and title, its times, then its
 * description, attachments, what the account handed in and the comments on it. The app submits
 * nothing, so the button at the foot opens the assignment on Moodle. The page fetches the
 * submission when it opens and on each return to the app, and shows the last copy meanwhile.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodleAssignmentScreen(
    browserPreference: String,
    onBack: () -> Unit,
    viewModel: MoodleAssignmentViewModel = hiltViewModel(),
) {
    val assignments by viewModel.assignments.collectAsStateWithLifecycle()
    MoodleSignedOutEffect(viewModel.signedIn, held = assignments, isCurrent = { it.isCurrent() }, onClose = onBack)
    val lookup by viewModel.lookup.collectAsStateWithLifecycle()
    val store by viewModel.store.collectAsStateWithLifecycle()
    val entry = (lookup as? MoodleAssignmentLookup.Found)?.entry
    // An id the cache does not know is not this account's homework, so a link from elsewhere opens nothing.
    if (lookup == MoodleAssignmentLookup.Missing) LaunchedEffect(Unit) { onBack() }
    val feed = remember(assignments, entry?.record?.assignId) { entry?.let { assignments?.submission(it.record.assignId) } }
    val submission = feed?.state?.collectAsStateWithLifecycle()?.value ?: MoodleFeed.State()
    // On each return to the app too: a submission made on Moodle shows once the app is back.
    LifecycleResumeEffect(entry?.record?.assignId, assignments) {
        entry?.let { viewModel.refresh(it) }
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = { MoodleTopBar(title = "", onBack = onBack, moodlePath = entry?.let(AssignmentPageRules::moodlePath)) },
        bottomBar = { entry?.let { AssignmentSubmitButton(it) } },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (entry == null) {
                MoodleFirstLoad(failure = null, onRetry = {})
            } else {
                AssignmentPageBody(
                    entry = entry,
                    courseName = AssignmentPageRules.courseName(entry, assignments),
                    submission = submission,
                    store = store,
                    browserPreference = browserPreference,
                    onRetry = { viewModel.refresh(entry) },
                )
            }
        }
    }
}

/** The page's body, under its bar and over its button: everything the page says of [entry]. */
@Composable
internal fun AssignmentPageBody(
    entry: MoodleAssignmentEntry,
    courseName: String,
    submission: MoodleFeed.State<MoodleAssignmentSubmission>,
    store: MoodleCourseStore?,
    browserPreference: String,
    onRetry: () -> Unit,
) {
    val record = entry.record
    val value = submission.value
    val moodlePath = AssignmentPageRules.moodlePath(entry)
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (courseName.isNotEmpty()) {
                Text(
                    text = courseName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                )
            }
            Text(
                text = record.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AssignmentPageRules.dueSeconds(entry)?.let {
                TimeRow(stringResource(R.string.assignment_detail_due), MoodleDates.minute(it))
            }
            AssignmentPageRules.submittedSeconds(entry, value)?.let {
                TimeRow(stringResource(R.string.assignment_detail_submitted_at), MoodleDates.minute(it))
            }
            value?.groupName?.let { TimeRow(stringResource(R.string.assignment_detail_group), it) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        submission.failureBesideCopy?.let { MoodleRefreshFailure(it, onRetry) }
        val description = rememberMoodleMarkdown(record.intro)
        if (!description.isNullOrBlank()) {
            PageSection(stringResource(R.string.assignment_detail_description)) {
                MoodleMarkdown(description, browserPreference, Modifier.fillMaxWidth())
            }
        }
        if (record.introAttachments.isNotEmpty()) {
            PageSection(stringResource(R.string.school_mail_attachments)) {
                MoodleAttachmentCard(store, record.introAttachments, browserPreference, moodlePath)
            }
        }
        if (!record.noSubmissions) {
            PageSection(stringResource(R.string.assignment_detail_submission)) {
                if (value == null) {
                    MoodleFirstLoad(failure = if (submission.isRefreshing) null else submission.failure, onRetry = onRetry)
                } else {
                    if (value.files.isNotEmpty()) {
                        MoodleAttachmentCard(store, value.files, browserPreference, moodlePath)
                    }
                    value.onlineText?.let { html ->
                        rememberMoodleMarkdown(html)?.let { MoodleMarkdown(it, browserPreference, Modifier.fillMaxWidth()) }
                    }
                    // For anything not handed in, a draft's files and text included.
                    if (!value.isSubmitted) {
                        Text(
                            text = stringResource(R.string.assignment_detail_not_submitted),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                        )
                    }
                }
            }
        }
        val comments = value?.comments.orEmpty()
        if (comments.isNotEmpty()) {
            PageSection(stringResource(R.string.assignment_detail_comments)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    comments.forEach { CommentItem(it, browserPreference) }
                }
            }
        }
    }
}

@Composable
private fun PageSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        content()
    }
}

/** A label with its value on the right, as the course detail's info rows. */
@Composable
private fun TimeRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun CommentItem(comment: MoodleSubmissionComment, browserPreference: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = listOf(MoodlePersonName.displayName(comment.authorName), MoodleDates.dateTime(comment.created))
                .filter { it.isNotEmpty() }
                .joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
        )
        rememberMoodleMarkdown(comment.content)?.let {
            MoodleMarkdown(it, browserPreference, Modifier.fillMaxWidth(), compact = true)
        }
    }
}

/** The button at the foot, which opens the assignment on Moodle to submit; nothing without a page to open. */
@Composable
internal fun AssignmentSubmitButton(entry: MoodleAssignmentEntry) {
    val path = AssignmentPageRules.submitPath(entry) ?: return
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.background) {
        Button(
            onClick = { openMoodlePage(context, path) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.assignment_detail_submit_on_moodle))
        }
    }
}
