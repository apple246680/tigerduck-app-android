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

    @Test
    fun `an attachment opens from a content URI another app may read once`() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "TigerDuckCache/moodle_open/B1/90/hw3.pdf").apply {
            parentFile!!.mkdirs()
            writeText("pdf")
        }

        val chooser = moodleFileIntent(context, file, "application/pdf")
        @Suppress("DEPRECATION")
        val view = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!

        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals("content", view.data!!.scheme)
        assertEquals("application/pdf", view.type)
        assertTrue(view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val uri = view.data.toString()
        assertFalse(uri.contains("token") || uri.contains("pluginfile"))
    }
}
