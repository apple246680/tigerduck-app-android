package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.app.Application
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** What leaves the app when an attachment opens: a content URI to the copy in the app, never a link with a key. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MoodleFileIntentTest {

    /**
     * One test for everything the FileProvider decides: it keeps the roots it read the first time
     * for the rest of the process, and each Robolectric test has an app folder of its own.
     */
    @Test
    fun `an attachment leaves as a content URI, a web page through the share sheet, nothing else at all`() {
        val context = RuntimeEnvironment.getApplication()
        fun file(path: String) = File(context.cacheDir, path).apply {
            parentFile!!.mkdirs()
            writeText("x")
        }

        @Suppress("DEPRECATION")
        val view = moodleFileIntent(context, file("TigerDuckCache/moodle_open/a1/s1/hw3.pdf"), "application/pdf")
            .getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals("content", view.data!!.scheme)
        assertEquals("application/pdf", view.type)
        assertTrue(view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertFalse(view.data.toString().let { it.contains("token") || it.contains("pluginfile") })

        for ((name, type) in listOf("notes.html" to "text/html", "page.xhtml" to "application/xhtml+xml", "logo.svg" to "image/svg+xml")) {
            @Suppress("DEPRECATION")
            val shared = moodleFileIntent(context, file("TigerDuckCache/moodle_open/a1/s1/$name"), type)
                .getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(name, Intent.ACTION_SEND, shared.action)
            assertTrue(shared.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        }

        val cached = file("TigerDuckCache/moodle/B1/courses/5/grades.json")
        val refused = runCatching { moodleFileIntent(context, cached, "application/json") }.exceptionOrNull()
        assertTrue("got $refused", refused is IllegalArgumentException)
    }

    @Test
    fun `Moodle's unknown document type is read from the file name`() {
        assertEquals("application/pdf", moodleFileType("document/unknown", "week1.pdf") { if (it == "pdf") "application/pdf" else null })
        assertEquals("application/pdf", moodleFileType(null, "week1.pdf") { if (it == "pdf") "application/pdf" else null })
        assertEquals("text/plain", moodleFileType("text/plain", "readme") { null })
    }
}
