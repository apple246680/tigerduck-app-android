package org.ntust.app.tigerduck.auth

import android.app.Application
import android.content.Context
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.AuthTokenManager.SignInAttempt
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.network.BulletinApiClient
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.ntust.app.tigerduck.push.AnonymousDeviceRequest
import org.ntust.app.tigerduck.push.DeviceRegisterRequest
import org.ntust.app.tigerduck.push.PushApiClient
import org.ntust.app.tigerduck.push.PushTokenIn
import org.ntust.app.tigerduck.push.SettingsDocumentApiClient
import org.ntust.app.tigerduck.push.SyncApiClient
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The backend sign-in [AuthTokenManager] makes on its own: before a request that needs a session
 * finds none, and on each return to the app
 * (https://github.com/tigerduck-app/tigerduck-app-android/issues/202).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AutomaticSignInTest {

    private lateinit var prefs: AppPreferences
    private lateinit var tokens: V3TokenStore
    private var clock = 1_800_000_000_000L
    private val backend = ScriptedBackend()

    /** Whether an account is stored for the stand-in sign-in to send. */
    private var accountStored = true

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = "http://127.0.0.1:9/v3" }
        // Never the real backend, even past the scripted one.
        assertTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-test", Context.MODE_PRIVATE))
        tokens.clear()
    }

    // No request without a token

    @Test
    fun `a protected request with no session signs in first and goes out with the new session`() = runTest {
        val api = PushApiClient(backend.client, prefs, manager())

        api.register(registerRequest())

        assertEquals(listOf(LOGIN, REGISTER), backend.endpoints())
        assertEquals("Bearer access-2", backend.received(REGISTER).single().authorization)
    }

    @Test
    fun `a protected request with no session sends nothing when the sign-in fails`() = runTest {
        backend.answer(LOGIN, 503)
        val api = PushApiClient(backend.client, prefs, manager())

        val failure = runCatching { api.register(registerRequest()) }.exceptionOrNull()

        assertTrue("got $failure", failure is NotSignedInException)
        assertEquals(listOf(LOGIN), backend.endpoints())
    }

    @Test
    fun `no client sends a request that needs a session without one`() = runTest {
        accountStored = false
        val manager = manager()
        val requests: List<suspend () -> Unit> = listOf(
            { PushApiClient(backend.client, prefs, manager).updateCredentials("moodle") },
            { BulletinApiClient(backend.client, prefs, manager).fetchSubscriptions() },
            { SettingsDocumentApiClient(backend.client, prefs, manager).read<JsonObject>("notification") },
            { SyncApiClient(manager, prefs, backend.client).fetchFullSync() },
        )

        val failures = requests.map { request -> runCatching { request() }.exceptionOrNull() }

        assertTrue("got $failures", failures.all { it is NotSignedInException })
        assertEquals(emptyList<String>(), backend.endpoints())
    }

    @Test
    fun `the announce goes out without a header and waits for no sign-in`() = runTest {
        tokens.save("access-1", "refresh-1", expiresAt = Long.MAX_VALUE)
        val api = PushApiClient(backend.client, prefs, manager())
        api.registerAnonymous(announceRequest())
        // Signed out, with an account whose sign-in would fail.
        tokens.clear()
        backend.answer(LOGIN, 503)

        api.registerAnonymous(announceRequest())

        assertEquals(listOf(ANNOUNCE, ANNOUNCE), backend.endpoints())
        assertEquals(listOf<String?>(null, null), backend.received(ANNOUNCE).map { it.authorization })
    }

    @Test
    fun `the unregister after a sign-out goes out with the header taken before the tokens were wiped`() = runTest {
        tokens.save("access-1", "refresh-1", expiresAt = Long.MAX_VALUE)
        val manager = manager()
        // In the order AuthService.logout() takes them.
        val captured = manager.currentAuthHeader()
        accountStored = false
        manager.logout()

        PushApiClient(backend.client, prefs, manager).unregister("device-1", captured)

        assertEquals(listOf(UNREGISTER), backend.endpoints())
        assertEquals("Bearer access-1", backend.received(UNREGISTER).single().authorization)
    }

    // Signing in again, and the pause after a failure

    @Test
    fun `a return to the app after a failed relogin signs in again once the pause has passed, and not before`() = runTest {
        tokens.save("access-1", "refresh-1", expiresAt = 0L)
        backend.answer(REFRESH, 401)
        backend.answer(LOGIN, 503)
        val manager = manager()
        assertNull(manager.authHeader())
        assertFalse(manager.isLoggedIn)

        // What MainActivity.onResume calls.
        clock += 299_000
        assertFalse(manager.signInIfSignedOut())
        assertEquals(1, backend.received(LOGIN).size)

        clock += 1_000
        assertTrue(manager.signInIfSignedOut())
        assertEquals(2, backend.received(LOGIN).size)
        assertEquals("refresh-2", tokens.refreshToken)
    }

    @Test
    fun `callers that find no session at once share one sign-in`() = runTest {
        val held = backend.hold(LOGIN)
        val manager = manager()

        val first = async { manager.authHeader() }
        val second = async { manager.authHeader() }
        held.arrived.await()
        held.release(200)

        assertEquals(listOf("Bearer access-2", "Bearer access-2"), listOf(first.await(), second.await()))
        assertEquals(1, backend.received(LOGIN).size)
    }

    @Test
    fun `an interactive sign-in ignores the pause`() = runTest {
        backend.answer(LOGIN, 503)
        val manager = manager()
        assertFalse(manager.signInIfSignedOut())

        assertTrue(manager.signInInteractively { manager.loginAsTheUser(it) })

        assertEquals(2, backend.received(LOGIN).size)
        assertEquals("refresh-2", tokens.refreshToken)
    }

    @Test
    fun `an automatic sign-in that starts during an interactive one waits for it and sends no login`() = runTest {
        val held = backend.hold(LOGIN)
        val manager = manager()

        val interactive = async { manager.signInInteractively { manager.loginAsTheUser(it) } }
        held.arrived.await()
        // Home's pull, once the account shows as signed in.
        val automatic = async { manager.signInIfSignedOut() }
        held.release(200)

        assertEquals(listOf(true, true), listOf(interactive.await(), automatic.await()))
        assertEquals(1, backend.received(LOGIN).size)
    }

    @Test
    fun `an interactive sign-in that fails holds the automatic one off`() = runTest {
        backend.answer(LOGIN, 503)
        val manager = manager()
        assertFalse(manager.signInInteractively { manager.loginAsTheUser(it) })

        // The registration the sign-in asks for, a moment later.
        assertFalse(manager.signInIfSignedOut())
        assertEquals(1, backend.received(LOGIN).size)

        clock += 300_000
        assertTrue(manager.signInIfSignedOut())
        assertEquals(2, backend.received(LOGIN).size)
    }

    @Test
    fun `a sign-out ends the pause`() = runTest {
        backend.answer(LOGIN, 503)
        val manager = manager()
        assertFalse(manager.signInIfSignedOut())

        manager.logout()

        assertTrue(manager.signInIfSignedOut())
        assertEquals(2, backend.received(LOGIN).size)
    }

    @Test
    fun `a sign-in that a sign-out overtook starts no pause`() = runTest {
        val manager = manager()
        backend.onLogin = { manager.logout() }
        assertFalse(manager.signInIfSignedOut())
        backend.onLogin = {}

        assertTrue(manager.signInIfSignedOut())
        assertEquals(2, backend.received(LOGIN).size)
    }

    @Test
    fun `with no account stored nothing goes out and no pause starts`() = runTest {
        accountStored = false
        val manager = manager()
        assertFalse(manager.signInIfSignedOut())

        accountStored = true

        assertTrue(manager.signInIfSignedOut())
        assertEquals(1, backend.received(LOGIN).size)
    }

    private fun manager() = AuthTokenManager(tokens, backend.client, prefs, "device-1", now = { clock }).apply {
        // Stands in for AuthService's, which sends the stored account through login().
        signInWithStoredAccount = { signIn ->
            if (accountStored) loginAsTheUser(signIn) else SignInAttempt.NOTHING_TO_SEND
        }
    }

    /** What AuthService.login() sends to the backend once the school took the password. */
    private suspend fun AuthTokenManager.loginAsTheUser(signIn: Int): SignInAttempt = try {
        login("B00000000", "password", "moodle", moodlePrivateToken = null, signInGeneration = signIn)
        SignInAttempt.SIGNED_IN
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SignInAttempt.FAILED
    }

    private fun registerRequest() = DeviceRegisterRequest(
        clientDeviceId = "device-1",
        pushToken = PushTokenIn(tokenValue = "fcm-1"),
    )

    private fun announceRequest() = AnonymousDeviceRequest(deviceId = "device-1", deviceClass = "phone")

    private companion object {
        const val LOGIN = "POST /auth/login"
        const val REFRESH = "POST /auth/refresh"
        const val REGISTER = "POST /devices/register"
        const val ANNOUNCE = "POST /devices/anonymous"
        const val UNREGISTER = "DELETE /devices/device-1"
    }
}

/**
 * Answers every request in place of the backend: the interceptor never calls proceed(). Each
 * endpoint answers 200 unless a test queued another status for it, and a [Held] answer keeps its
 * request waiting until the test releases it. A sign-in answers with the pair "access-2" and
 * "refresh-2".
 */
private class ScriptedBackend {
    class Received(val endpoint: String, val authorization: String?)

    class Held {
        val arrived = CompletableDeferred<Unit>()
        private val reply = CompletableFuture<Int>()

        fun release(code: Int) {
            reply.complete(code)
        }

        fun await(): Int = reply.get(10, TimeUnit.SECONDS)
    }

    private val received = CopyOnWriteArrayList<Received>()
    private val queued = ConcurrentHashMap<String, ConcurrentLinkedQueue<Any>>()

    /** Runs as each sign-in arrives, before its answer. */
    @Volatile
    var onLogin: () -> Unit = {}

    val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain -> answer(chain.request()) }
        .build()

    fun answer(endpoint: String, vararg codes: Int) {
        codes.forEach { queue(endpoint).add(it) }
    }

    fun hold(endpoint: String): Held = Held().also { queue(endpoint).add(it) }

    fun received(endpoint: String): List<Received> = received.filter { it.endpoint == endpoint }

    fun endpoints(): List<String> = received.map { it.endpoint }

    private fun queue(endpoint: String) = queued.getOrPut(endpoint) { ConcurrentLinkedQueue() }

    private fun answer(request: Request): Response {
        val endpoint = request.method + " " + request.url.encodedPath.removePrefix("/v3")
        received += Received(endpoint, request.header("Authorization"))
        if (endpoint == "POST /auth/login") onLogin()
        val code = when (val next = queued[endpoint]?.poll()) {
            is Held -> {
                next.arrived.complete(Unit)
                next.await()
            }
            is Int -> next
            else -> 200
        }
        val body = when {
            code !in 200..299 -> """{"detail":"refused"}"""
            endpoint == "POST /auth/login" ->
                """{"access_token":"access-2","refresh_token":"refresh-2","expires_in":900,"device_id":"device-1"}"""
            else -> """{"device_id":"device-1","push_token_id":1}"""
        }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }
}
