package org.ntust.app.tigerduck.notification

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.MainActivity
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Where a class reminder's tap lands: the class table. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ClassReminderTapTest {

    private val context: Application = RuntimeEnvironment.getApplication()

    @Test
    fun `tapping a class reminder opens the class table`() {
        val now = System.currentTimeMillis()
        ClassPreparingNotificationReceiver.post(
            context,
            notificationId = 7,
            courseName = "Data Structures",
            classroom = "TR-412",
            instructor = "",
            startMs = now + 10 * 60_000L,
            endMs = now + 60 * 60_000L,
            leadTimeMs = 10 * 60_000L,
        )

        val posted: Notification = shadowOf(context.getSystemService(NotificationManager::class.java))
            .getNotification(7)
        val tap = shadowOf(posted.contentIntent)
        assertTrue(tap.isActivity)
        assertTrue("a tap target others cannot fill in", tap.isImmutable)
        assertEquals(Intent.ACTION_VIEW, tap.savedIntent.action)
        assertEquals(MainActivity::class.java.name, tap.savedIntent.component?.className)
        assertEquals("tigerduck://classtable", tap.savedIntent.dataString)
    }
}
