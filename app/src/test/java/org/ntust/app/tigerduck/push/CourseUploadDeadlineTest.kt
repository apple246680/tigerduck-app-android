package org.ntust.app.tigerduck.push

import android.app.Application
import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * A course upload ends within its deadline, whatever the server's pace: the course outbox holds
 * its send lock through it, and a course delete or term reset waits on that lock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CourseUploadDeadlineTest {

    @Test
    fun `a course upload has a deadline for the whole call`() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = AppPreferences(context)
        val client = OkHttpClient()
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-upload-deadline-test", Context.MODE_PRIVATE))
        val api = PushApiClient(client, prefs, AuthTokenManager(tokens, client, prefs, "device-1"))

        val call = api.uploadCall(Request.Builder().url("http://127.0.0.1:9/v3/sync/courses/upload").build())

        assertEquals(TimeUnit.SECONDS.toNanos(PushApiClient.UPLOAD_CALL_TIMEOUT_SECONDS), call.timeout().timeoutNanos())
    }
}
