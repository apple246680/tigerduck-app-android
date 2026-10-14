package org.ntust.app.tigerduck.ui.screen.coursemoodle

import android.app.Application
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `an attachment leaves as a content URI, a web page or XML through the share sheet, nothing else at all`() {
        val context = RuntimeEnvironment.getApplication()
        fun file(path: String) = File(context.cacheDir, path).apply {
            parentFile!!.mkdirs()
            writeText("x")
        }

        val chooser = moodleFileIntent(context, file("TigerDuckCache/moodle_open/a1/s1/hw3.pdf"), "application/pdf")
        // The system's own chooser, which says so itself when no app takes the file.
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertNull("no title of the app's own", chooser.getCharSequenceExtra(Intent.EXTRA_TITLE))
        @Suppress("DEPRECATION")
        val view = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals("the grant rides the wrapped intent's clip through the chooser", view.data, view.clipData!!.getItemAt(0).uri)
        assertEquals("and the chooser carries it on", view.data, chooser.clipData!!.getItemAt(0).uri)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals("content", view.data!!.scheme)
        assertEquals("application/pdf", view.type)
        assertTrue(view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertFalse(view.data.toString().let { it.contains("token") || it.contains("pluginfile") })

        val scripted = listOf(
            "notes.html" to "text/html", "page.xhtml" to "application/xhtml+xml", "logo.svg" to "image/svg+xml",
            "feed.xml" to "text/xml", "data.xml" to "application/xml", "news.rss" to "application/rss+xml",
            "upper.html" to "Text/HTML; charset=utf-8",
        )
        for ((name, type) in scripted) {
            val shareChooser = moodleFileIntent(context, file("TigerDuckCache/moodle_open/a1/s1/$name"), type)
            @Suppress("DEPRECATION")
            val shared = shareChooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(name, Intent.ACTION_SEND, shared.action)
            assertEquals(name, Intent.normalizeMimeType(type), shared.type)
            @Suppress("DEPRECATION")
            val stream = shared.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals("content", stream.scheme)
            assertEquals(stream, shared.clipData!!.getItemAt(0).uri)
            assertTrue(shared.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(stream, shareChooser.clipData!!.getItemAt(0).uri)
            assertTrue(shareChooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
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
