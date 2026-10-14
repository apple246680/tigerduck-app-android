package org.ntust.app.tigerduck.ui.screen.settings

import android.app.Application
import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.auth.ScriptedBackend
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.shared.Course
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Use Local's requests fail, rather than go quietly unsent, once course sync
 * is off ([PushCourseSyncBackend]). [PushApiClient] skips them without a word
 * then, so a term reset that had landed was followed by no upload, and every
 * other device hid the whole term — with nothing to ask the question again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PushCourseSyncBackendTest {

    private val backend = ScriptedBackend()
    private lateinit var prefs: AppPreferences
    private lateinit var courses: PushCourseSyncBackend

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3" }
        // Never the real backend, even past the scripted one.
        assertTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-use-local-test", Context.MODE_PRIVATE))
        tokens.save("access", "refresh", Long.MAX_VALUE)
        val api = PushApiClient(backend.client, prefs, AuthTokenManager(tokens, backend.client, prefs, "device-1"))
        courses = PushCourseSyncBackend(api, prefs)
    }

    private suspend fun fails(request: suspend () -> Unit): Boolean = runCatching { request() }.isFailure

    @Test
    fun `with course sync off every request fails and none is sent`() = runTest {
        prefs.syncCourses = false

        assertTrue(fails { courses.deleteAllCourses("1151") })
        assertTrue(fails { courses.uploadCourses(listOf(Course(courseNo = "CS1", courseName = "CS1")), "1151") })
        assertTrue(fails { courses.deleteCourse("client:1151:CS1") })
        assertEquals(emptyList<String>(), backend.endpoints())
    }

    @Test
    fun `with cloud sync off every request fails and none is sent`() = runTest {
        prefs.cloudSyncEnabled = false

        assertTrue(fails { courses.deleteAllCourses("1151") })
        assertTrue(fails { courses.uploadCourses(emptyList(), "1151") })
        assertEquals(emptyList<String>(), backend.endpoints())
    }
}
