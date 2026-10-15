package org.ntust.app.tigerduck.ui.screen.classtable

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.component.TigerDuckDialog
import org.ntust.app.tigerduck.ui.theme.ContentAlpha

/**
 * Renames [course], prefilled with its current name. Shared by a cell's long-press menu and the
 * course detail's rename button, so both read the default from the same source.
 */
@Composable
internal fun CourseRenameDialog(
    viewModel: ClassTableViewModel,
    course: Course,
    onDismiss: () -> Unit,
) {
    val courses by viewModel.courses.collectAsStateWithLifecycle()
    val liveCourse = courses.find { it.courseNo == course.courseNo } ?: course
    val defaultName = remember(liveCourse) { viewModel.defaultNameFor(liveCourse) }
    var renameText by rememberSaveable(course.courseNo) {
        mutableStateOf(liveCourse.customCourseName ?: defaultName)
    }
    val trimmed = renameText.trim()
    val hasOverride = liveCourse.customCourseName != null
    // Revert is meaningful when a stored override exists OR the user has
    // typed away from the default; either way it restores the default.
    val canRevert = hasOverride || (trimmed.isNotEmpty() && trimmed != defaultName)
    TigerDuckDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.class_table_rename_title),
        confirmText = stringResource(R.string.action_confirm),
        onConfirm = {
            viewModel.setCustomCourseName(course.courseNo, renameText)
            onDismiss()
        },
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text(stringResource(R.string.class_table_course_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.class_table_rename_default_label, defaultName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            if (hasOverride) {
                                viewModel.revertCourseName(course.courseNo)
                                onDismiss()
                            } else {
                                renameText = defaultName
                            }
                        },
                        enabled = canRevert,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(stringResource(R.string.class_table_rename_revert), maxLines = 1)
                    }
                }
            }
        },
    )
}
