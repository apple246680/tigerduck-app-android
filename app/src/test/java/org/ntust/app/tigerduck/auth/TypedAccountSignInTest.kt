package org.ntust.app.tigerduck.auth

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.AuthTokenManager.SignInAttempt
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.debug.DebugFixtureStore
import org.ntust.app.tigerduck.debug.DemoModeInterceptor
import org.ntust.app.tigerduck.network.NtustSessionManager
import org.ntust.app.tigerduck.network.SsoLoginError
import org.ntust.app.tigerduck.network.SsoLoginService
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The sign-in with a password the user typed, against hand-made school pages, while the school's
 * refusal of the stored password is latched: the latch lifts only together with the store of the
 * new password, whenever the sign-in is cancelled.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TypedAccountSignInTest {

    private lateinit var latch: PersistedStoredPasswordLatch
    private lateinit var school: SsoLoginService
    private var stored: Pair<String, String>? = STUDENT to OLD_PASSWORD

    /** Whether the school takes the password posted to it. */
    private var accepts = true

    /** Runs as the school gets the password, before its answer. */
    private var onPost: () -> Unit = {}

    /** Whether the refusal still held when the new password was stored, once one was. */
    private var latchedAtStore: Boolean? = null

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val latchPrefs = context.getSharedPreferences("typed-sign-in-test", Context.MODE_PRIVATE)
        latchPrefs.edit().clear().commit()
        latch = PersistedStoredPasswordLatch(latchPrefs) { stored }
        latch.markRejected(latch.generation, STUDENT, OLD_PASSWORD, "Wrong account or password")
        val sessionManager = NtustSessionManager(AppPreferences(context), DemoModeInterceptor(DebugFixtureStore(context)))
        val client = OkHttpClient.Builder().addInterceptor { chain -> answer(chain.request()) }.build()
        school = SsoLoginService(sessionManager, latch) { client }
    }

    private fun typedSignIn() = TypedAccountSignIn(
        schoolSignIn = { studentId, password -> school.signIn(SERVICE, studentId, password) },
        signInInteractively = { signIn -> signIn(GENERATION) == SignInAttempt.SIGNED_IN },
        storedStudentId = { stored?.first },
        writeAccount = { studentId, password ->
            latchedAtStore = latch.rejection.value != null
            stored = studentId to password
        },
        latch = latch,
    )

    @Test
    fun `the refusal holds until the new password is stored, and lifts with it`() = runTest {
        val signedIn = typedSignIn().signIn(STUDENT, NEW_PASSWORD) { _, _ -> SignInAttempt.SIGNED_IN }

        assertTrue(signedIn)
        assertEquals("nothing lifted it before the new password was stored", true, latchedAtStore)
        assertEquals(STUDENT to NEW_PASSWORD, stored)
        assertFalse(latch.isRejected)
    }

    @Test
    fun `a sign-in cancelled after the school took the password keeps it, with no refusal held`() = runTest {
        val libraryStarted = CompletableDeferred<Unit>()
        val signIn = launch {
            typedSignIn().signIn(STUDENT, NEW_PASSWORD) { _, _ ->
                // The library's sign-in, which the user leaves before it answers.
                libraryStarted.complete(Unit)
                awaitCancellation()
            }
        }
        libraryStarted.await()

        signIn.cancelAndJoin()

        assertEquals(STUDENT to NEW_PASSWORD, stored)
        assertFalse(latch.isRejected)
    }

    @Test
    fun `a sign-in cancelled while the school answers keeps the old password and its refusal`() = runTest {
        lateinit var signIn: Job
        // The user leaves while the school is still answering.
        onPost = { signIn.cancel() }
        signIn = launch { typedSignIn().signIn(STUDENT, NEW_PASSWORD) { _, _ -> SignInAttempt.SIGNED_IN } }

        signIn.join()

        assertEquals(STUDENT to OLD_PASSWORD, stored)
        assertNotNull(latch.rejection.value)
        assertTrue(latch.isRejected)
    }

    @Test
    fun `a sign-in the school refuses stores nothing and keeps the refusal`() = runTest {
        accepts = false

        val failure = runCatching {
            typedSignIn().signIn(STUDENT, NEW_PASSWORD) { _, _ -> SignInAttempt.SIGNED_IN }
        }.exceptionOrNull()

        assertTrue("got $failure", failure is SsoLoginError.LoginFailed)
        assertEquals(STUDENT to OLD_PASSWORD, stored)
        assertTrue(latch.isRejected)
    }

    private fun answer(request: Request): Response {
        val (url, html) = if (request.method == "POST") {
            onPost()
            if (accepts) SERVICE to SERVICE_PAGE else LOGIN_URL to LOGIN_PAGE_REFUSED
        } else {
            // Every visit to the service lands on the school's sign-in form.
            LOGIN_URL to LOGIN_PAGE
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
        const val OLD_PASSWORD = "old-password"
        const val NEW_PASSWORD = "new-password"
        const val GENERATION = 7
        const val SERVICE = "https://courseselection.ntust.edu.tw/"
        const val LOGIN_URL = "https://ssoam2.ntust.edu.tw/account/login?ReturnUrl=%2Fconnect"

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
            """<div class="validation-summary-errors"><ul><li>Wrong account or password</li></ul></div><form""",
        )

        const val SERVICE_PAGE = "<html><body>Course selection</body></html>"
    }
}
