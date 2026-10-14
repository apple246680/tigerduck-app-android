package org.ntust.app.tigerduck.network

import android.app.Application
import android.content.Context
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.PersistedStoredPasswordLatch
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.debug.DebugFixtureStore
import org.ntust.app.tigerduck.debug.DemoModeInterceptor
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [SsoLoginService] against hand-made school pages: an automatic sign-in the school refuses
 * latches, a latched one posts no password, and a sign-in the user types lifts the latch
 * (https://github.com/tigerduck-app/tigerduck-app-android/issues/163).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SsoLoginLatchTest {

    private lateinit var latch: PersistedStoredPasswordLatch
    private lateinit var service: SsoLoginService
    private var stored: Pair<String, String>? = STUDENT to PASSWORD

    /** Whether the school takes the password posted to it. */
    private var accepts = false
    private val posts = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val latchPrefs = context.getSharedPreferences("sso-latch-test", Context.MODE_PRIVATE)
        latchPrefs.edit().clear().commit()
        latch = PersistedStoredPasswordLatch(latchPrefs) { stored }
        val sessionManager = NtustSessionManager(AppPreferences(context), DemoModeInterceptor(DebugFixtureStore(context)))
        val school = OkHttpClient.Builder().addInterceptor { chain -> answer(chain.request()) }.build()
        service = SsoLoginService(sessionManager, latch) { school }
    }

    @Test
    fun `an automatic sign-in the school refuses latches with its message`() = runTest {
        val failure = runCatching { service.ensureServiceLogin(SERVICE, STUDENT, PASSWORD) }.exceptionOrNull()

        assertTrue("got $failure", failure is SsoLoginError.LoginFailed)
        assertEquals(REASON, (failure as SsoLoginError.LoginFailed).reason)
        assertEquals(1, posts.size)
        assertTrue(latch.isRejected)
        assertEquals(REASON, latch.rejection.value?.reason)
    }

    @Test
    fun `a latched automatic sign-in posts no password`() = runTest {
        latch.markRejected(latch.generation, STUDENT, PASSWORD, REASON)
        accepts = true

        val failure = runCatching { service.ensureServiceLogin(SERVICE, STUDENT, PASSWORD) }.exceptionOrNull()

        assertTrue("got $failure", failure is SsoLoginError.LoginFailed)
        assertEquals(REASON, (failure as SsoLoginError.LoginFailed).reason)
        assertEquals(emptyList<String>(), posts)
    }

    @Test
    fun `a sign-in the user types posts despite the latch and lifts it`() = runTest {
        latch.markRejected(latch.generation, STUDENT, PASSWORD, REASON)
        accepts = true

        assertTrue(service.signIn(SERVICE, STUDENT, "new-password"))

        assertEquals(1, posts.size)
        assertFalse(latch.isRejected)
    }

    @Test
    fun `a sign-in the user types that the school refuses latches nothing`() = runTest {
        val failure = runCatching { service.signIn(SERVICE, STUDENT, PASSWORD) }.exceptionOrNull()

        assertTrue("got $failure", failure is SsoLoginError.LoginFailed)
        assertEquals(REASON, (failure as SsoLoginError.LoginFailed).reason)
        assertFalse(latch.isRejected)
    }

    @Test
    fun `an automatic sign-in the school accepts latches nothing`() = runTest {
        accepts = true

        assertTrue(service.ensureServiceLogin(SERVICE, STUDENT, PASSWORD))

        assertEquals(1, posts.size)
        assertFalse(latch.isRejected)
    }

    private fun answer(request: Request): Response {
        val (url, html) = when {
            request.method == "POST" -> {
                posts += request.url.toString()
                if (accepts) SERVICE to SERVICE_PAGE else LOGIN_URL to LOGIN_PAGE_REFUSED
            }
            // Every visit to the service lands on the school's sign-in form.
            else -> LOGIN_URL to LOGIN_PAGE
        }
        return Response.Builder()
            // As if OkHttp had followed the redirect to it.
            .request(request.newBuilder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(html.toResponseBody("text/html; charset=utf-8".toMediaType()))
            .build()
    }

    private companion object {
        const val STUDENT = "B00000000"
        const val PASSWORD = "old-password"
        const val SERVICE = "https://courseselection.ntust.edu.tw/"
        const val LOGIN_URL = "https://ssoam2.ntust.edu.tw/account/login?ReturnUrl=%2Fconnect"
        const val REASON = "Wrong account or password"

        val LOGIN_PAGE = """
            <html><body>
            <form id="loginForm" action="/account/login" method="post">
              <input type="hidden" name="__RequestVerificationToken" value="made-up-token" />
              <input type="text" name="Username" />
              <input type="password" name="Password" />
            </form>
            </body></html>
        """.trimIndent()

        val LOGIN_PAGE_REFUSED = LOGIN_PAGE.replace(
            "<form",
            """<div class="validation-summary-errors"><ul><li>$REASON</li></ul></div><form""",
        )

        const val SERVICE_PAGE = "<html><body>Course selection</body></html>"
    }
}
