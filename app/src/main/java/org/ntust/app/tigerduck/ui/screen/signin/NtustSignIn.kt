package org.ntust.app.tigerduck.ui.screen.signin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.ui.screen.settings.LoginSheet

/**
 * The school sign-in sheet any page can open with "Sign in again"
 * ([org.ntust.app.tigerduck.ui.AppState.ntustSignInSheetRequested]). Hosted once, above the pages.
 * It signs in as the stored account, so its student ID is fixed; another student signs out in
 * Settings first, where the sign-out asks about what goes with the account.
 */
@Composable
fun NtustSignInSheetHost(viewModel: NtustSignInViewModel = hiltViewModel()) {
    if (!viewModel.sheetRequested) return
    val isLoggingIn by viewModel.isLoggingIn.collectAsStateWithLifecycle()
    val loginError by viewModel.loginError.collectAsStateWithLifecycle()
    val storedStudentId = viewModel.storedStudentId
    LoginSheet(
        title = stringResource(R.string.settings_account_ntust_system),
        usernamePlaceholder = stringResource(R.string.sign_in_student_id),
        passwordPlaceholder = stringResource(R.string.sign_in_password),
        initialUsername = storedStudentId.orEmpty(),
        usernameEditable = storedStudentId == null,
        uppercaseInput = true,
        isLoggingIn = isLoggingIn,
        loginError = loginError,
        onLogin = viewModel::signIn,
        onDismiss = viewModel::dismissSheet,
    )
}
