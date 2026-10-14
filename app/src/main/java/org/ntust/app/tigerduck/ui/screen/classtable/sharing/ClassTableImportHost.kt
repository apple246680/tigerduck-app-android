package org.ntust.app.tigerduck.ui.screen.classtable.sharing

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.sharing.ClassTableShareCode
import org.ntust.app.tigerduck.ui.component.TigerDuckDialog
import org.ntust.app.tigerduck.ui.screen.classtable.sharing.ClassTableImportModel.Prompt
import org.ntust.app.tigerduck.ui.theme.ContentAlpha
import org.ntust.app.tigerduck.ui.theme.TigerDuckAppTheme
import java.text.NumberFormat
import java.time.Instant
import java.util.Date

/**
 * Presents the import [viewModel] holds, over whichever page is showing: the iOS scanner page
 * mapped to Material 3 (the camera, and Choose from photos below it), the confirm dialog, and the
 * prompts. The page stays up behind them, so a refused code can be followed by another; it closes
 * once the person is saved, which [onImported] hears, or when the user closes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassTableImportHost(viewModel: ClassTableImportViewModel, onImported: () -> Unit) {
    val model = viewModel.session.collectAsStateWithLifecycle().value ?: return
    val state by model.state.collectAsStateWithLifecycle()
    val latestOnImported by rememberUpdatedState(onImported)
    LaunchedEffect(state.isFinished) {
        if (state.isFinished) {
            viewModel.end()
            latestOnImported()
        }
    }
    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.readPicture(uri)
    }

    Dialog(
        onDismissRequest = viewModel::end,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Dark whatever the app's theme, as on iOS: the page is the camera's picture.
        TigerDuckAppTheme(darkTheme = true, accentColor = MaterialTheme.colorScheme.primary) {
            Scaffold(
                containerColor = Color.Black,
                contentColor = Color.White,
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.class_table_import)) },
                        navigationIcon = {
                            IconButton(onClick = viewModel::end) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black),
                    )
                },
                bottomBar = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(bottom = 24.dp, top = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        FilledTonalButton(
                            onClick = {
                                pickPicture.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            enabled = state.isIdle,
                            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                        ) {
                            Icon(
                                Icons.Outlined.PhotoLibrary,
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.IconSize),
                            )
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.class_table_import_choose_photo))
                        }
                    }
                },
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    QrScannerCamera(onScanned = model::scanned, modifier = Modifier.fillMaxSize())
                    // An update has no confirm dialog to wait in, so it waits here, as a picture does.
                    if (state.isReadingImage || (state.isLookingUp && state.pending == null)) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }

    state.pending?.let { code -> ImportConfirmDialog(model, code, state) }

    state.prompt?.let { prompt -> ImportPromptDialog(model, prompt) }
}

/**
 * The step before a new person is saved: their nickname, and what the share code holds. Import
 * waits on the course lookup, which back and a tap outside leave alone; Cancel stops it.
 */
@Composable
private fun ImportConfirmDialog(model: ClassTableImportModel, code: ClassTableShareCode, state: ClassTableImportModel.State) {
    val context = LocalContext.current
    val rows = remember(code) {
        importConfirmRows(
            code = code,
            joinList = { android.icu.text.ListFormatter.getInstance().format(it) },
            formatCount = { NumberFormat.getIntegerInstance().format(it) },
            formatTime = { instant ->
                val date = Date.from(instant)
                android.text.format.DateFormat.getDateFormat(context).format(date) + " " +
                    android.text.format.DateFormat.getTimeFormat(context).format(date)
            },
        )
    }
    TigerDuckDialog(
        onDismissRequest = model::cancel,
        title = stringResource(R.string.class_table_import),
        confirmText = stringResource(R.string.action_import),
        onConfirm = model::importPending,
        confirmEnabled = state.canImport,
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = model::cancel,
        dismissable = !state.isLookingUp,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = state.nickname,
                onValueChange = model::setNickname,
                label = { Text(stringResource(R.string.class_table_nickname)) },
                singleLine = true,
                enabled = !state.isLookingUp,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { model.importPending() }),
                modifier = Modifier.fillMaxWidth(),
            )
            rows.forEach { row ->
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(row.title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.SECONDARY),
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = row.value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (state.isLookingUp) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ImportPromptDialog(model: ClassTableImportModel, prompt: Prompt) {
    val title = when (prompt) {
        Prompt.Invalid, Prompt.NoCode, Prompt.OwnCode, is Prompt.SaveFailed ->
            stringResource(R.string.class_table_import_failed)
        is Prompt.Replace -> stringResource(R.string.class_table_import_replace_title, prompt.job.nickname)
        is Prompt.LookupFailed -> stringResource(R.string.class_table_import_lookup_failed)
    }
    val message = when (prompt) {
        Prompt.Invalid -> stringResource(R.string.class_table_import_invalid_message)
        Prompt.NoCode -> stringResource(R.string.class_table_import_no_code_message)
        Prompt.OwnCode -> stringResource(R.string.class_table_import_own_code_message)
        is Prompt.Replace -> stringResource(R.string.class_table_import_replace_message)
        is Prompt.LookupFailed, is Prompt.SaveFailed -> null
    }
    when (prompt) {
        Prompt.Invalid, Prompt.NoCode, Prompt.OwnCode -> TigerDuckDialog(
            onDismissRequest = model::dismissPrompt,
            title = title,
            message = message,
            confirmText = stringResource(R.string.action_confirm),
        )
        is Prompt.Replace -> TigerDuckDialog(
            onDismissRequest = model::dismissPrompt,
            title = title,
            message = message,
            confirmText = stringResource(R.string.action_update),
            onConfirm = { model.lookUp(prompt.job) },
            dismissText = stringResource(R.string.action_cancel),
        )
        is Prompt.LookupFailed -> TigerDuckDialog(
            onDismissRequest = model::dismissPrompt,
            title = title,
            confirmText = stringResource(R.string.action_retry),
            onConfirm = { model.lookUp(prompt.job) },
            dismissText = stringResource(R.string.action_cancel),
        )
        is Prompt.SaveFailed -> TigerDuckDialog(
            onDismissRequest = model::dismissPrompt,
            title = title,
            confirmText = stringResource(R.string.action_retry),
            onConfirm = { model.lookUp(prompt.job) },
            dismissText = stringResource(R.string.action_cancel),
        )
    }
}

/** One line of the confirm dialog: what it is, and what the code holds. */
internal data class ImportConfirmRow(@param:StringRes val title: Int, val value: String)

/**
 * In order: the student ID, the terms ("114-2, 115-1"), the number of courses and the share
 * time, which only our own format carries; TAT's may have no student ID either.
 */
internal fun importConfirmRows(
    code: ClassTableShareCode,
    joinList: (List<String>) -> String,
    formatCount: (Int) -> String,
    formatTime: (Instant) -> String,
): List<ImportConfirmRow> = buildList {
    code.studentId?.let { add(ImportConfirmRow(R.string.sign_in_student_id, it)) }
    add(ImportConfirmRow(R.string.class_table_semester_picker_label, joinList(code.terms.map { semesterLabel(it.semester) })))
    add(ImportConfirmRow(R.string.class_table_import_course_count, formatCount(code.terms.sumOf { it.courseNos.size })))
    code.sharedAt?.let { add(ImportConfirmRow(R.string.class_table_import_shared_at, formatTime(it))) }
}

/** `1151` as `115-1`, as the term picker shows it. */
private fun semesterLabel(semester: String) = semester.dropLast(1) + "-" + semester.takeLast(1)
