package org.ntust.app.tigerduck.push

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * What the TigerSync status card shows as its latest error when a
 * registration or a push preference update fails. An exception without a
 * message used to show its class name, which a release build shortens to
 * R8's name for it, such as "v44".
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app this test does not need.
@Config(application = Application::class)
class PushDiagnosticTextTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `a failure without a message shows the generic failure text`() {
        assertEquals("Failed", IOException().diagnosticText(context))
        assertEquals("Failed", IOException("  ").diagnosticText(context))
    }

    @Test
    @Config(qualifiers = "zh-rTW")
    fun `the generic failure text is the user's language`() {
        assertEquals("失敗", IllegalStateException().diagnosticText(context))
    }

    @Test
    fun `a failure's own message is still what it shows`() {
        assertEquals("timeout", IOException("timeout").diagnosticText(context))
    }
}
