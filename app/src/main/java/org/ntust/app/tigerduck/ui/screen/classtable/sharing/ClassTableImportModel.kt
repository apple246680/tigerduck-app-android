package org.ntust.app.tigerduck.ui.screen.classtable.sharing

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.data.sharing.ClassTableShareCode
import org.ntust.app.tigerduck.data.sharing.ClassTableShareCodeError
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableStore
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.util.logSummary

/**
 * One import of a class table from a share code: the code the camera or a picture gave, the
 * prompts it raises, the nickname, the course lookup and the person it saves. The import sheet,
 * the confirm screen and the prompts show it; a test passes its own lookup. The iOS app's
 * `ClassTableImportModel`, which shares its rules and its strings.
 */
class ClassTableImportModel(
    private val store: ImportedClassTableStore,
    private val signedInStudentId: () -> String?,
    /** False once the account the import began under has signed out, or another signed in. */
    private val isCurrent: () -> Boolean,
    /**
     * One term's courses from NTUST's course query, without the codes it does not know; throws
     * when the query fails.
     */
    private val lookup: suspend (semester: String, courseNos: List<String>) -> List<Course>,
    private val scope: CoroutineScope,
) {
    /** What a lookup imports: the code, and the nickname a new person is saved under. */
    data class ImportJob(val code: ClassTableShareCode, val nickname: String)

    /** The prompts that start a lookup carry its job, so their buttons need nothing else. */
    sealed interface Prompt {
        /** A wrong checksum, layout or course code, or a format version this app does not know. */
        data object Invalid : Prompt

        data object NoCode : Prompt

        data object OwnCode : Prompt

        /** The code's student ID is imported already; updating keeps that person's nickname. */
        data class Replace(val job: ImportJob) : Prompt

        data class LookupFailed(val job: ImportJob) : Prompt

        data class SaveFailed(val job: ImportJob) : Prompt
    }

    data class State(
        val prompt: Prompt? = null,
        /** The code the confirm screen shows, with the nickname field above its details. */
        val pending: ClassTableShareCode? = null,
        val nickname: String = "",
        val isLookingUp: Boolean = false,
        val isReadingImage: Boolean = false,
        /** Set once the person is saved, or once the account signed out meanwhile; the import closes then. */
        val isFinished: Boolean = false,
    ) {
        val isIdle: Boolean get() = prompt == null && pending == null && !isLookingUp && !isReadingImage && !isFinished

        val canImport: Boolean get() = nickname.isNotBlank() && !isLookingUp && !isFinished
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var lookupJob: Job? = null
    private var imageJob: Job? = null

    // --- Reading a code ---

    /**
     * A QR code a scanner that goes on scanning read. Another app's code is passed over so
     * scanning goes on, and so is any code while something else is on screen. Returns whether
     * the code was taken.
     */
    fun scanned(text: String): Boolean {
        if (!_state.value.isIdle) return false
        when (val reading = reading(text)) {
            Reading.NotShareCode -> return false
            Reading.Invalid -> _state.update { it.copy(prompt = Prompt.Invalid) }
            is Reading.Code -> open(reading.code)
        }
        return true
    }

    /**
     * The QR codes found in a picture, or the one a scanner that stops after one code read: the
     * first share code, or a prompt when there is none.
     */
    fun imageRead(payloads: List<String>) {
        if (!_state.value.isIdle) return
        val readings = payloads.map(::reading)
        val code = readings.firstNotNullOfOrNull { (it as? Reading.Code)?.code }
        if (code != null) {
            open(code)
        } else {
            val prompt = if (readings.any { it == Reading.Invalid }) Prompt.Invalid else Prompt.NoCode
            _state.update { it.copy(prompt = prompt) }
        }
    }

    /**
     * Reads a picture: [read] returns its QR codes, or null when it could not be opened. The import
     * is held while it runs, so nothing else can start one.
     */
    fun readImage(read: suspend () -> List<String>?) {
        if (!_state.value.isIdle) return
        _state.update { it.copy(isReadingImage = true) }
        imageJob = scope.launch {
            val payloads = try {
                read().orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A picture that could not be read holds no code the import can use.
                Log.w(TAG, "Could not read the picture: ${e.logSummary()}")
                emptyList()
            }
            _state.update { it.copy(isReadingImage = false) }
            imageJob = null
            if (!isCurrent()) {
                _state.update { it.copy(isFinished = true) }
                return@launch
            }
            imageRead(payloads)
        }
    }

    private fun open(code: ClassTableShareCode) {
        val studentId = code.studentId
        if (studentId != null) {
            if (studentId == signedInStudentId()?.uppercase()) {
                _state.update { it.copy(prompt = Prompt.OwnCode) }
                return
            }
            val person = store.tables.value.person(studentId)
            if (person != null) {
                _state.update { it.copy(prompt = Prompt.Replace(ImportJob(code, person.nickname))) }
                return
            }
        }
        _state.update { it.copy(pending = code, nickname = studentId.orEmpty()) }
    }

    // --- Importing ---

    fun setNickname(nickname: String) {
        _state.update { it.copy(nickname = nickname) }
    }

    fun importPending() {
        val state = _state.value
        val pending = state.pending ?: return
        if (!state.canImport) return
        lookUp(ImportJob(pending, state.nickname))
    }

    /**
     * Saves nothing until every term's lookup has answered, and nothing at all after a failure, a
     * cancel or a sign-out. A failed save keeps the confirm screen, with a prompt to try again.
     */
    fun lookUp(job: ImportJob) {
        val state = _state.value
        if (state.isLookingUp || state.isFinished) return
        _state.update { it.copy(prompt = null, isLookingUp = true) }
        lookupJob = scope.launch {
            val courses = courses(job.code)
            if (!isCurrent()) {
                _state.update { it.copy(isLookingUp = false, isFinished = true) }
                return@launch
            }
            if (courses == null) {
                _state.update { it.copy(isLookingUp = false, prompt = Prompt.LookupFailed(job)) }
                return@launch
            }
            val person = store.add(job.code, job.nickname, courses)
            if (person == null) {
                _state.update { it.copy(isLookingUp = false, prompt = Prompt.SaveFailed(job)) }
                return@launch
            }
            store.show(person.id)
            _state.update { it.copy(isLookingUp = false, isFinished = true) }
        }
    }

    private suspend fun courses(code: ClassTableShareCode): Map<String, List<Course>>? = try {
        code.terms.associate { it.semester to lookup(it.semester, it.courseNos) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Could not look up an imported class table: ${e.logSummary()}")
        null
    }

    /** Cancel on a failed lookup's prompt leaves the confirm screen as it was. */
    fun dismissPrompt() {
        _state.update { it.copy(prompt = null) }
    }

    /** A lookup still running saves nothing once this returns, even if its answer comes later. */
    fun cancel() {
        imageJob?.cancel()
        imageJob = null
        lookupJob?.cancel()
        lookupJob = null
        _state.update { it.copy(prompt = null, pending = null, isLookingUp = false, isReadingImage = false) }
    }

    // --- Decoding ---

    private sealed interface Reading {
        data object NotShareCode : Reading

        data object Invalid : Reading

        data class Code(val code: ClassTableShareCode) : Reading
    }

    private fun reading(text: String): Reading = try {
        Reading.Code(ClassTableShareCode.decode(text))
    } catch (_: ClassTableShareCodeError.UnknownPrefix) {
        Reading.NotShareCode
    } catch (e: ClassTableShareCodeError) {
        Log.i(TAG, "Share code refused: ${e.javaClass.simpleName}")
        Reading.Invalid
    }

    private companion object {
        const val TAG = "ClassTableImport"
    }
}
