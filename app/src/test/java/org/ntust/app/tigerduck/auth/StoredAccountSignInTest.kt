package org.ntust.app.tigerduck.auth

import android.app.Application
import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.AuthTokenManager.SignInAttempt
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [StoredAccountSignIn], the automatic sign-in AuthService hands [AuthTokenManager], against a
 * scripted backend, with stand-ins for the stored account and the Moodle token harvest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class StoredAccountSignInTest {

    private lateinit var manager: AuthTokenManager
    private val backend = ScriptedBackend()

    private var demo = false
    private var studentId: String? = "B00000000"
    private var password: String? = "password"
    private var storedToken: String? = "stored"
    private var harvest: suspend () -> String = { "fresh" }
    private var harvests = 0
    private var afterSignIns = 0

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3" }
        // Never the real backend, even past the scripted one.
        assertTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-test", Context.MODE_PRIVATE))
        tokens.clear()
        manager = AuthTokenManager(tokens, backend.client, prefs, "device-1")
    }

    @Test
    fun `it signs in with a Moodle token harvested for the purpose`() = runTest {
        assertEquals(SignInAttempt.SIGNED_IN, signIn())

        assertEquals(listOf<String?>("fresh"), backend.sentMoodleTokens())
        assertTrue(manager.isLoggedIn)
        assertEquals(1, afterSignIns)
    }

    @Test
    fun `a harvest that fails falls back to the stored token`() = runTest {
        harvest = { throw IllegalStateException("Moodle unavailable") }

        assertEquals(SignInAttempt.SIGNED_IN, signIn())

        assertEquals(listOf<String?>("stored"), backend.sentMoodleTokens())
    }

    @Test
    fun `with no account, the demo account, or neither a password nor a token, nothing goes out`() = runTest {
        studentId = null
        assertEquals(SignInAttempt.NOTHING_TO_SEND, signIn())

        studentId = "B00000000"
        demo = true
        assertEquals(SignInAttempt.NOTHING_TO_SEND, signIn())

        demo = false
        password = null
        storedToken = null
        assertEquals(SignInAttempt.NOTHING_TO_SEND, signIn())

        assertEquals(0, harvests)
        assertEquals(emptyList<String>(), backend.endpoints())
    }

    @Test
    fun `a harvest that fails with no token stored is a failure and sends no login`() = runTest {
        harvest = { throw IllegalStateException("Moodle unavailable") }
        storedToken = null

        assertEquals(SignInAttempt.FAILED, signIn())

        assertEquals(emptyList<String>(), backend.endpoints())
    }

    @Test
    fun `a sign-out during the harvest sends no login with the token it falls back to`() = runTest {
        val generation = manager.signInGeneration
        harvest = {
            manager.logout()
            // The next account's sign-in stored its token meanwhile, and the
            // harvest, which stores nothing for an account that left, fails.
            storedToken = "next-account"
            throw IllegalStateException("signed out")
        }

        assertEquals(SignInAttempt.FAILED, signIn(generation))

        assertEquals(emptyList<String>(), backend.endpoints())
    }

    @Test
    fun `a refused login is a failure and starts nothing after it`() = runTest {
        backend.answer(LOGIN, 503)

        assertEquals(SignInAttempt.FAILED, signIn())

        assertEquals(1, backend.received(LOGIN).size)
        assertEquals(0, afterSignIns)
    }

    private suspend fun signIn(generation: Int = manager.signInGeneration): SignInAttempt = StoredAccountSignIn(
        tokens = manager,
        isDemo = { demo },
        studentId = { studentId },
        password = { password },
        storedMoodleToken = { storedToken },
        harvestMoodleToken = {
            harvests++
            harvest()
        },
        afterSignIn = { afterSignIns++ },
    ).signIn(generation)

    private companion object {
        const val LOGIN = "POST /auth/login"
    }
}
