@file:OptIn(ExperimentalCoroutinesApi::class)

package org.ntust.app.tigerduck

import android.app.Application
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.sharing.ClassTableShareCode
import org.ntust.app.tigerduck.data.sharing.ImportedClassTableStore
import org.ntust.app.tigerduck.shared.Course
import org.ntust.app.tigerduck.ui.screen.classtable.sharing.ClassTableImportModel
import org.ntust.app.tigerduck.ui.screen.classtable.sharing.ClassTableImportModel.Prompt
import org.ntust.app.tigerduck.ui.screen.classtable.sharing.ClassTableImportViewModel
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * A class table share code opened as a link: from the camera, Google Lens or another QR app, a
 * tapped link, or tigerduck.app's "Open in TigerDuck" button. Any page or app can open one, so it
 * takes exactly the steps a scanned code does, and only for a signed-in student.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ShareCodeLinkTest {

    private val context: Application = RuntimeEnvironment.getApplication()
    private val account = "B11300000"
    private val store by lazy {
        ImportedClassTableStore({ ImportedClassTableStore.fileIn(context) }, { account }, Dispatchers.Unconfined)
    }

    private val sharedAt = Instant.parse("2026-10-14T03:20:00Z")
    private val code = ClassTableShareCode.encode(
        "B11300001", sharedAt, listOf(ClassTableShareCode.Term("1151", listOf("CS1010001", "MA1010001"))),
    )!!
    private val otherCode = ClassTableShareCode.encode(
        "B11300002", sharedAt, listOf(ClassTableShareCode.Term("1151", listOf("CS1010001"))),
    )!!
    private val calculus = Course(courseNo = "MA1010001", courseName = "Calculus")

    /** What follows `/T/`. */
    private fun body(code: String) = code.removePrefix(ClassTableShareCode.PREFIX)

    /** The code as it is written, the form App Links deliver, and the web page's button. */
    private val links = listOf(
        code,
        "https://tigerduck.app/T/${body(code)}",
        "tigerduck://share/${body(code)}",
    )

    private fun linkIntent(link: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE)

    private fun take(link: String, restored: Boolean = false, mayImport: Boolean = true) =
        takeSharedClassTableCode(linkIntent(link), restored, mayImport)

    private fun TestScope.model(
        lookup: suspend (String, List<String>) -> List<Course> = { _, _ -> listOf(calculus) },
    ) = ClassTableImportModel(store, { account }, { true }, lookup, this)

    /** The import a link opens over [open], as MainActivity hands it over. */
    private suspend fun TestScope.openLink(link: String, open: ClassTableImportModel? = null, next: ClassTableImportModel = model()) =
        ClassTableImportViewModel.linkedImport(store, { open }, { next }, take(link)!!)!!

    @Test
    fun `the code as written, in lower case and from the web page's button reach the decoder as the same code`() {
        val expected = ClassTableShareCode.decode(code)

        links.forEach { link ->
            val text = take(link)
            assertNotNull(link, text)
            assertEquals(link, expected, ClassTableShareCode.decode(text!!))
        }
        // Passed on as it came: the decoder trims and upper-cases it itself.
        assertEquals(code, take(code))
        assertEquals(links[1], take(links[1]))
    }

    @Test
    fun `a link whose account signs out while the store is read opens nothing`() = runTest {
        val open = model()
        var signedIn = true
        val next = model()
        val result = ClassTableImportViewModel.linkedImport(
            store, { open }, { next }, take(links[0])!!,
            // Signed out by the time the store's read is done.
            stillCurrent = { signedIn.also { signedIn = false } && signedIn },
        )
        advanceUntilIdle()

        assertEquals(null, result)
        assertEquals(null, next.state.value.pending)
        assertTrue(store.tables.value.people.isEmpty())
    }

    @Test
    fun `a link opens the confirm step a scanned code does, and saves nothing by itself`() = runTest {
        links.forEach { link ->
            val model = openLink(link)
            advanceUntilIdle()

            assertEquals(link, ClassTableShareCode.decode(code), model.state.value.pending)
            assertEquals(link, "B11300001", model.state.value.nickname)
        }
        assertTrue(store.tables.value.people.isEmpty())
    }

    @Test
    fun `a link that starts the app asks to update a person imported already, once the store is read`() = runTest {
        // Imported in an earlier run; this run's store has not read its file yet.
        ImportedClassTableStore({ ImportedClassTableStore.fileIn(context) }, { account }, Dispatchers.Unconfined)
            .add(ClassTableShareCode.decode(code), "Ming", mapOf("1151" to listOf(calculus)))

        val model = openLink(links[1])

        assertEquals("Ming", (model.state.value.prompt as? Prompt.Replace)?.job?.nickname)
        assertNull(model.state.value.pending)
    }

    @Test
    fun `a T URL that is not a share code shows the malformed message`() = runTest {
        listOf(
            "https://tigerduck.app/T/",
            "https://tigerduck.app/T/hello",
            "HTTPS://TIGERDUCK.APP/T/2.B11300001",
            code.dropLast(1) + "A",
            "tigerduck://share",
            "tigerduck://share/hello",
        ).forEach { link ->
            val model = openLink(link)

            assertEquals(link, Prompt.Invalid, model.state.value.prompt)
            assertNull(link, model.state.value.pending)
        }
    }

    @Test
    fun `other links are left for the rest of the app`() {
        listOf(
            "https://tigerduck.app/",
            "https://tigerduck.app/guide",
            "https://example.com/T/${body(code)}",
            "http://tigerduck.app/T/${body(code)}",
            "https://tigerduck.app.example.com/T/${body(code)}",
            "tigerduck://classtable",
            "tigerduck://announcement/42",
            "tigerduck://Share/${body(code)}",
        ).forEach { link ->
            val intent = linkIntent(link)

            assertNull(link, takeSharedClassTableCode(intent, restored = false, mayImport = true))
            assertEquals(link, link, intent.dataString)
        }
    }

    @Test
    fun `a link before sign-in or during onboarding imports nothing, and is not kept for later`() {
        val intent = linkIntent(code)

        assertNull(takeSharedClassTableCode(intent, restored = false, mayImport = false))
        assertNull("the code is taken off the intent", intent.data)
        assertNull("so signing in later reads nothing", takeSharedClassTableCode(intent, restored = false, mayImport = true))
    }

    @Test
    fun `a link opens once per delivery, and not again for a rebuilt Activity or from Recents`() {
        val intent = linkIntent(code)

        assertNotNull(takeSharedClassTableCode(intent, restored = false, mayImport = true))
        assertNull(intent.data)
        assertNull(takeSharedClassTableCode(intent, restored = false, mayImport = true))

        assertNull(take(code, restored = true))
        val fromRecents = linkIntent(code).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertNull(takeSharedClassTableCode(fromRecents, restored = false, mayImport = true))
        assertNull(fromRecents.data)
    }

    @Test
    fun `a second link replaces an import that is open, and nothing of the first is saved`() = runTest {
        val answer = CompletableDeferred<List<Course>>()
        val first = openLink(code, next = model { _, _ -> answer.await() })
        first.importPending()
        advanceUntilIdle()
        assertTrue(first.state.value.isLookingUp)

        val second = openLink("tigerduck://share/${body(otherCode)}", open = first)
        answer.complete(listOf(calculus))
        advanceUntilIdle()

        assertNull("the earlier code is dropped", first.state.value.pending)
        assertFalse(first.state.value.isLookingUp)
        assertEquals("B11300002", second.state.value.pending?.studentId)
        assertTrue(store.tables.value.people.isEmpty())
    }

    @Test
    fun `a second link replaces a prompt the first one raised`() = runTest {
        val first = openLink("https://tigerduck.app/T/hello")
        assertEquals(Prompt.Invalid, first.state.value.prompt)

        val second = openLink(code, open = first)

        assertNull(first.state.value.prompt)
        assertNull(second.state.value.prompt)
        assertEquals("B11300001", second.state.value.pending?.studentId)
    }

    @Test
    fun `a QR app or a browser opens the share code link and the web page's button in MainActivity`() {
        val packageManager = context.packageManager
        listOf("https://tigerduck.app/T/${body(code)}", "tigerduck://share/${body(code)}").forEach { link ->
            // A QR app may leave out the BROWSABLE category a browser adds.
            listOf(Intent(Intent.ACTION_VIEW, Uri.parse(link)), linkIntent(link)).forEach { intent ->
                assertEquals(link, MainActivity::class.java.name, packageManager.resolveActivity(intent, 0)?.activityInfo?.name)
            }
        }
    }

    @Test
    fun `the rest of the site, and the code's upper-case spelling, stay with the browser`() {
        val packageManager = context.packageManager
        listOf(
            "https://tigerduck.app/",
            "https://tigerduck.app/guide",
            "https://tigerduck.app/t/${body(code)}",
            // Android compares the scheme and host case-sensitively, and only the lower-case host is
            // verified; the web page's button opens the app for a scanner that opens the browser.
            code,
        ).forEach { link ->
            val resolved = packageManager.resolveActivity(linkIntent(link), 0)?.activityInfo?.name
            assertNotEquals(link, MainActivity::class.java.name, resolved)
        }
    }
}
