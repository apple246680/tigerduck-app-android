package org.ntust.app.tigerduck.ui.screen.classtable.sharing

import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.sharing.ClassTableShareCode
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableStore
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.screen.classtable.sharing.ClassTableImportModel.ImportJob
import org.ntust.app.tigerduck.ui.screen.classtable.sharing.ClassTableImportModel.Prompt
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.time.Instant

/** The import's steps over a real store: which prompt a code raises, the lookup, and what is saved. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ClassTableImportModelTest {

    private val context: Application = RuntimeEnvironment.getApplication()
    private var storeFile = ImportedClassTableStore.fileIn(context)
    private val account = "B11300000"
    private val store by lazy { ImportedClassTableStore({ storeFile }, { account }, Dispatchers.Unconfined) }
    private var current = true

    private val sharedAt = Instant.parse("2026-10-14T03:20:00Z")
    private val mingCode = ClassTableShareCode.encode(
        "B11300001", sharedAt, listOf(ClassTableShareCode.Term("1151", listOf("CS1010001", "MA1010001"))),
    )!!
    private val calculus = Course(courseNo = "MA1010001", courseName = "Calculus")

    private fun TestScope.model(
        lookup: suspend (String, List<String>) -> List<Course> = { _, _ -> listOf(calculus) },
    ) = ClassTableImportModel(store, { account.lowercase() }, { current }, lookup, this)

    @Test
    fun `another app's QR code is passed over while scanning, and a damaged share code is refused`() = runTest {
        val model = model()

        assertFalse(model.scanned("https://example.com"))
        assertTrue(model.state.value.isIdle)

        assertTrue(model.scanned(mingCode.dropLast(1) + "A"))
        assertEquals(Prompt.Invalid, model.state.value.prompt)
        // Nothing else is taken while the prompt is up.
        assertFalse(model.scanned(mingCode))
    }

    @Test
    fun `a picture opens its first share code, or says why it has none`() = runTest {
        val model = model()

        model.imageRead(listOf("https://example.com"))
        assertEquals(Prompt.NoCode, model.state.value.prompt)
        model.dismissPrompt()

        model.imageRead(listOf("https://example.com", "HTTPS://TIGERDUCK.APP/T/1.X"))
        assertEquals(Prompt.Invalid, model.state.value.prompt)
        model.dismissPrompt()

        model.imageRead(listOf("https://example.com", mingCode.lowercase()))
        assertEquals("B11300001", model.state.value.pending?.studentId)
        // The nickname starts as the student ID.
        assertEquals("B11300001", model.state.value.nickname)
    }

    @Test
    fun `the user's own code is refused`() = runTest {
        val own = ClassTableShareCode.encode(account, sharedAt, listOf(ClassTableShareCode.Term("1151", listOf("CS1010001"))))!!
        val model = model()

        model.imageRead(listOf(own))

        assertEquals(Prompt.OwnCode, model.state.value.prompt)
        assertNull(model.state.value.pending)
    }

    @Test
    fun `an import saves the person under the nickname with the courses looked up, and shows them`() = runTest {
        val asked = mutableListOf<Pair<String, List<String>>>()
        val model = model { semester, courseNos -> asked += semester to courseNos; listOf(calculus) }
        model.imageRead(listOf(mingCode))

        model.setNickname("  ")
        model.importPending()
        assertFalse(model.state.value.isLookingUp)
        model.setNickname("Ming")
        model.importPending()
        advanceUntilIdle()

        assertEquals(listOf("1151" to listOf("CS1010001", "MA1010001")), asked)
        assertTrue(model.state.value.isFinished)
        val person = store.tables.value.shownPerson!!
        assertEquals("Ming", person.nickname)
        assertEquals(listOf("Calculus"), person.courses("1151").map { it.courseName })
    }

    @Test
    fun `a student ID imported already asks to update it, which keeps its nickname`() = runTest {
        model().run {
            imageRead(listOf(mingCode))
            setNickname("Ming")
            importPending()
        }
        advanceUntilIdle()
        val model = model()

        model.imageRead(listOf(mingCode))
        val prompt = model.state.value.prompt as Prompt.Replace
        assertEquals("Ming", prompt.job.nickname)
        model.lookUp(prompt.job)
        advanceUntilIdle()

        assertTrue(model.state.value.isFinished)
        assertEquals(listOf("Ming"), store.tables.value.people.map { it.nickname })
    }

    @Test
    fun `a failed lookup saves nothing and offers a retry, which imports`() = runTest {
        var fails = true
        val model = model { _, _ -> if (fails) throw IOException("offline") else listOf(calculus) }
        model.imageRead(listOf(mingCode))
        model.importPending()
        advanceUntilIdle()

        val prompt = model.state.value.prompt as Prompt.LookupFailed
        assertEquals(ImportJob(model.state.value.pending!!, "B11300001"), prompt.job)
        assertTrue(store.tables.value.people.isEmpty())

        fails = false
        model.lookUp(prompt.job)
        advanceUntilIdle()
        assertTrue(model.state.value.isFinished)
        assertEquals(1, store.tables.value.people.size)
    }

    @Test
    fun `a failed save keeps the confirm step and offers a retry`() = runTest {
        val obstacle = File(context.noBackupFilesDir, "obstacle").apply { parentFile!!.mkdirs(); writeText("") }
        storeFile = File(obstacle, "imported_class_tables.json")
        val model = model()
        model.imageRead(listOf(mingCode))
        model.importPending()
        advanceUntilIdle()

        assertTrue(model.state.value.prompt is Prompt.SaveFailed)
        assertEquals("B11300001", model.state.value.pending?.studentId)
        assertFalse(model.state.value.isFinished)

        obstacle.delete()
        model.lookUp((model.state.value.prompt as Prompt.SaveFailed).job)
        advanceUntilIdle()
        assertTrue(model.state.value.isFinished)
    }

    @Test
    fun `a lookup that ends after a sign-out saves nothing and closes the import`() = runTest {
        val answer = CompletableDeferred<List<Course>>()
        val model = model { _, _ -> answer.await() }
        model.imageRead(listOf(mingCode))
        model.importPending()
        advanceUntilIdle()

        current = false
        answer.complete(listOf(calculus))
        advanceUntilIdle()

        assertTrue(model.state.value.isFinished)
        assertTrue(store.tables.value.people.isEmpty())
    }

    @Test
    fun `a cancelled lookup saves nothing even when its answer comes later`() = runTest {
        val answer = CompletableDeferred<List<Course>>()
        val model = model { _, _ -> answer.await() }
        model.imageRead(listOf(mingCode))
        model.importPending()
        advanceUntilIdle()
        assertTrue(model.state.value.isLookingUp)

        model.cancel()
        answer.complete(listOf(calculus))
        advanceUntilIdle()

        assertTrue(model.state.value.isIdle)
        assertTrue(store.tables.value.people.isEmpty())
    }

    @Test
    fun `a picture is read while nothing else can start, and its codes then open`() = runTest {
        val picture = CompletableDeferred<List<String>?>()
        val model = model()

        model.readImage { picture.await() }
        advanceUntilIdle()
        assertTrue(model.state.value.isReadingImage)
        assertFalse(model.scanned(mingCode))

        picture.complete(null)
        advanceUntilIdle()
        assertEquals(Prompt.NoCode, model.state.value.prompt)
    }

    @Test
    fun `the confirm step lists the student ID, the terms, the course count and the share time`() {
        val code = ClassTableShareCode.decode(mingCode)

        val rows = importConfirmRows(code, { it.joinToString(", ") }, { it.toString() }, { it.toString() })

        assertEquals(
            listOf(
                ImportConfirmRow(R.string.sign_in_student_id, "B11300001"),
                ImportConfirmRow(R.string.class_table_semester_picker_label, "115-1"),
                ImportConfirmRow(R.string.class_table_import_course_count, "2"),
                ImportConfirmRow(R.string.class_table_import_shared_at, sharedAt.toString()),
            ),
            rows,
        )
        val tat = ClassTableShareCode.decode("HTTPS://NTUST-TAT.WEB.APP/S/TAT11142         CS1010001")
        assertEquals(
            listOf(R.string.class_table_semester_picker_label, R.string.class_table_import_course_count),
            importConfirmRows(tat, { it.joinToString() }, { it.toString() }, { "" }).map { it.title },
        )
    }

    @Test
    fun `a picture that fails to read says it has no code, and the import goes on`() = runTest {
        val model = model()

        model.readImage { throw IllegalStateException("decoder gave up") }
        advanceUntilIdle()

        assertEquals(Prompt.NoCode, model.state.value.prompt)
        assertFalse(model.state.value.isReadingImage)
    }
}

