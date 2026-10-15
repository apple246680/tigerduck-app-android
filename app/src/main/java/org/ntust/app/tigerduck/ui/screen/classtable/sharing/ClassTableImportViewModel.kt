package org.ntust.app.tigerduck.ui.screen.classtable.sharing

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ntust.app.tigerduck.auth.AuthService
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableStore
import org.ntust.app.tigerduck.data.sharing.ImportedCourseLookup
import org.ntust.app.tigerduck.data.sharing.QrCodeImageReader
import javax.inject.Inject

/**
 * The app's one class table import, which the class table's ⋯ menu and the Class Table tab's
 * menu both start. Held at the activity, above the pages, so either can start it from any tab.
 */
@HiltViewModel
class ClassTableImportViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val store: ImportedClassTableStore,
    private val authService: AuthService,
    private val lookup: ImportedCourseLookup,
) : ViewModel() {
    private val _session = MutableStateFlow<ClassTableImportModel?>(null)

    /** The import under way, null while none is. */
    val session: StateFlow<ClassTableImportModel?> = _session.asStateFlow()

    init {
        // The prompts ask the store who is imported already.
        viewModelScope.launch { store.load() }
        viewModelScope.launch {
            // A sign-out ends an import: it would save into the store the sign-out empties.
            authService.authState.collect { signedIn -> if (!signedIn) end() }
        }
    }

    /**
     * Starts an import for whoever is signed in now, or for no one, as the tab's menu can start one
     * signed out; it saves nothing once someone signs out or in meanwhile.
     */
    fun start() {
        if (_session.value != null) return
        _session.value = ClassTableImportModel(
            store = store,
            signedInStudentId = { authService.storedStudentId },
            isCurrent = sameSignIn({ authService.authState.value }, { authService.storedStudentId }),
            lookup = lookup::courses,
            scope = viewModelScope,
        )
    }

    internal companion object {
        /**
         * A check that whoever was signed in when it was made, or no one, still is: an import
         * saves only then, as a sign-out empties the store and another account must not inherit it.
         */
        fun sameSignIn(signedIn: () -> Boolean, studentId: () -> String?): () -> Boolean {
            val startedAs = signedIn() to studentId()
            return { (signedIn() to studentId()) == startedAs }
        }
    }

    fun end() {
        _session.value?.cancel()
        _session.value = null
    }

    /** Reads the QR codes in the picture the photo picker returned. */
    fun readPicture(uri: Uri) {
        _session.value?.readImage {
            withContext(Dispatchers.Default) { QrCodeImageReader.read(context, uri) }
        }
    }
}
