package org.ntust.app.tigerduck.network

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.PersistedStoredPasswordLatch
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.debug.DebugFixtureStore
import org.ntust.app.tigerduck.debug.DemoModeInterceptor
import org.ntust.app.tigerduck.network.moodle.MoodleWebservice
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [MoodleTokenService]'s Moodle token harvest against hand-made Moodle and SSO pages, with the
 * app-wide stored-password latch: what its renewals post, and what a sign-in by hand stores
 * (https://github.com/tigerduck-app/tigerduck-app-android/issues/163, #212 and #300).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MoodleTokenServiceLatchTest {

    private lateinit var latchPrefs: SharedPreferences
    private val tokens = Tokens()
    private val posts = CopyOnWriteArrayList<String>()

    /** Whether SSO takes the password posted to it. */
    private var accepts = true

    /** Runs as each SSO sign-in arrives, before its answer. */
    private var onPost: () -> Unit = {}

    private val school = OkHttpClient.Builder().addInterceptor { chain -> answer(chain.request()) }.build()

    private class Tokens : MoodleTokenCoordinator.Tokens {
        override var token: String? = null
        override var studentId: String? = STUDENT
        override var password: String? = PASSWORD

        override fun storeIfSignedIn(studentId: String, token: String): Boolean {
            if (this.studentId != studentId) return false
            this.token = token
            return true
        }
    }

    @Before
    fun setUp() {
        latchPrefs = RuntimeEnvironment.getApplication().getSharedPreferences("moodle-latch-test", Context.MODE_PRIVATE)
        latchPrefs.edit().clear().commit()
    }

    /** A service and latch as a fresh process has them, over the same persisted latch. */
    private fun TestScope.service(): Pair<MoodleTokenService, PersistedStoredPasswordLatch> {
        val latch = PersistedStoredPasswordLatch(latchPrefs) {
            val id = tokens.studentId
            val password = tokens.password
            if (id == null || password == null) null else id to password
        }
        val service = MoodleTokenService(
            clientFor = { school },
            tokens = tokens,
            passwordLatch = latch,
            scope = backgroundScope,
            now = { 1_800_000_000_000L },
        )
        return service to latch
    }

    @Test
    fun `a renewal signs in with the stored account and stores the token`() = runTest(UnconfinedTestDispatcher()) {
        val (service, _) = service()

        assertEquals(WSTOKEN, service.renewToken(null))

        assertEquals(WSTOKEN, tokens.token)
        assertEquals(1, posts.size)
    }

    @Test
    fun `a latched renewal posts no password`() = runTest(UnconfinedTestDispatcher()) {
        val (service, latch) = service()
        latch.markRejected(latch.generation, STUDENT, PASSWORD, REASON)

        val failure = runCatching { service.renewToken(null) }.exceptionOrNull()

        assertTrue("got $failure", failure is MoodleWebserviceError.SsoLoginRejected)
        assertEquals(emptyList<String>(), posts)
    }

    @Test
    fun `a refusal stops renewals in the next process too`() = runTest(UnconfinedTestDispatcher()) {
        accepts = false
        val (first, firstLatch) = service()
        val failure = runCatching { first.renewToken(null) }.exceptionOrNull()
        assertTrue("got $failure", failure is MoodleWebserviceError.SsoLoginRejected)
        assertEquals(REASON, firstLatch.rejection.value?.reason)
        assertEquals(1, posts.size)

        // The app restarts: new instances, over the latch the last process left.
        accepts = true
        val (second, _) = service()
        val again = runCatching { second.renewToken(null) }.exceptionOrNull()

        assertTrue("got $again", again is MoodleWebserviceError.SsoLoginRejected)
        assertEquals("the refused password is not posted again", 1, posts.size)
    }

    @Test
    fun `a Moodle call whose token is dead renews it through the latch`() = runTest(UnconfinedTestDispatcher()) {
        val (service, latch) = service()
        latch.markRejected(latch.generation, STUDENT, PASSWORD, REASON)
        tokens.token = "dead"
        val context = RuntimeEnvironment.getApplication()
        val sessionManager = NtustSessionManager(AppPreferences(context), DemoModeInterceptor(DebugFixtureStore(context)))
        val webservice = MoodleWebservice(sessionManager, service)

        val failure = runCatching {
            webservice.withToken<Unit> { throw MoodleWebserviceError.InvalidToken() }
        }.exceptionOrNull()

        assertTrue("got $failure", failure is MoodleWebserviceError.SsoLoginRejected)
        assertEquals(emptyList<String>(), posts)
    }

    @Test
    fun `a sign-in by hand posts what was typed even while latched, and lifts the latch`() = runTest(UnconfinedTestDispatcher()) {
        val (service, latch) = service()
        latch.markRejected(latch.generation, STUDENT, PASSWORD, REASON)

        assertEquals(WSTOKEN, service.obtainToken(STUDENT, "new-password"))

        assertEquals(1, posts.size)
        assertNull(latch.rejection.value)
    }

    @Test
    fun `a sign-out during the sign-in by hand stores nothing of the departing account`() = runTest(UnconfinedTestDispatcher()) {
        val (service, _) = service()
        onPost = {
            tokens.studentId = null
            tokens.password = null
        }

        val failure = runCatching { service.obtainToken(STUDENT, PASSWORD) }.exceptionOrNull()

        assertTrue("got $failure", failure is MoodleWebserviceError.MissingStoredCredentials)
        assertNull(tokens.token)
    }

    private fun answer(request: Request): Response {
        val (url, html) = when {
            request.method == "POST" -> {
                posts += request.url.toString()
                onPost()
                if (accepts) TOKEN_URL to tokenPage() else LOGIN_URL to LOGIN_PAGE_REFUSED
            }
            // The launch lands on the school's sign-in form, as for a device with no SSO session.
            else -> LOGIN_URL to LOGIN_PAGE
        }
        return Response.Builder()
            // As if OkHttp had followed the redirects to it.
            .request(request.newBuilder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(html.toResponseBody("text/html; charset=utf-8".toMediaType()))
            .build()
    }

    private fun tokenPage(): String {
        val payload = Base64.encodeToString("signature:::$WSTOKEN:::private".toByteArray(), Base64.NO_WRAP)
        return """<html><body><a href="moodlemobile://token=$payload">Open</a></body></html>"""
    }

    private companion object {
        const val STUDENT = "B00000000"
        const val PASSWORD = "old-password"
        const val WSTOKEN = "made-up-wstoken"
        const val REASON = "Wrong account or password"
        const val LOGIN_URL = "https://ssoam2.ntust.edu.tw/account/login?ReturnUrl=%2Fconnect"
        const val TOKEN_URL = "https://moodle2.ntust.edu.tw/admin/tool/mobile/launch.php?confirmed=1"

        val LOGIN_PAGE = """
            <html><body>
            <form action="/account/login" method="post">
              <input type="hidden" name="__RequestVerificationToken" value="made-up-antiforgery" />
              <input type="hidden" name="ClientId" value="moodle" />
              <input type="hidden" name="ReturnUrl" value="/connect" />
              <input type="hidden" name="Uri" value="" />
              <input type="text" name="Username" />
              <input type="password" name="Password" />
            </form>
            </body></html>
        """.trimIndent()

        val LOGIN_PAGE_REFUSED = LOGIN_PAGE.replace(
            "<form",
            """<div class="validation-summary-errors"><ul><li>$REASON</li></ul></div><form""",
        )
    }
}
