package org.ntust.app.tigerduck.push

import android.app.Application
import android.content.Context
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.AuthTokenManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.UiLanguageMonitor
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.debug.DebugFixtureStore
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [PushRegistrationService] registering the device again after a registration failed:
 * https://github.com/tigerduck-app/tigerduck-app-android/issues/194. Play only, as fdroid has
 * no FCM token and never registers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PushRegistrationServiceTest {

    private lateinit var context: Application
    private lateinit var prefs: AppPreferences
    private val backend = FakeBackend()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs = AppPreferences(context)
        prefs.hasCompletedOnboarding = true
    }

    /** Signed in, with an access token that needs no refresh. */
    private fun TestScope.service(): PushRegistrationService {
        val tokens = V3TokenStore(context.getSharedPreferences("v3-tokens-test", Context.MODE_PRIVATE))
        tokens.save("access-1", "refresh-1", expiresAt = Long.MAX_VALUE)
        val auth = AuthTokenManager(tokens, backend.client, prefs, deviceUuid = "device-1")
        return PushRegistrationService(
            context = context,
            identity = PushIdentity(prefs, context),
            api = PushApiClient(backend.client, prefs, auth),
            authTokenManager = auth,
            appPreferences = prefs,
            uiLanguage = UiLanguageMonitor(prefs),
            scope = backgroundScope,
            fixtures = DebugFixtureStore(context),
        )
    }

    /** Registers "fcm-1" while the backend refuses it the way it refuses a request with no session. */
    private suspend fun PushRegistrationService.failRegistration() {
        backend.answer(REGISTER, 401)
        update("fcm-1")
        awaitRegistrations()
        assertNotNull("the registration failed", diagnostic.value.lastError)
    }

    // Turning TigerSync on

    @Test
    fun `turning TigerSync on after a failed registration registers again and clears the error`() = runTest {
        val service = service()
        service.failRegistration()

        service.updateCloudSyncEnabled(true)
        service.awaitRegistrations()

        assertEquals(2, backend.received(REGISTER).size)
        assertNull(service.diagnostic.value.lastError)
        assertTrue(service.diagnostic.value.isRegistered)
    }

    @Test
    fun `turning TigerSync on registers again when the server refuses the switch, and still reports it`() = runTest {
        val service = service()
        service.failRegistration()
        // A device the server holds no row for: only a registration adds one.
        backend.answer(PREFERENCES, 404)

        val refused = runCatching { service.updateCloudSyncEnabled(true) }.exceptionOrNull()
        service.awaitRegistrations()

        assertTrue("pushCloudSyncEnabled records the switch only on success", refused is PushApiException)
        assertEquals(2, backend.received(REGISTER).size)
        assertNull(service.diagnostic.value.lastError)
    }

    @Test
    fun `turning TigerSync on after a registration went through sends no second one`() = runTest {
        val service = service()
        service.update("fcm-1")
        service.awaitRegistrations()

        service.updateCloudSyncEnabled(true)
        service.awaitRegistrations()

        assertEquals(1, backend.received(REGISTER).size)
    }

    @Test
    fun `turning TigerSync on starts the automatic retry's backoff over`() = runTest {
        val service = service()
        service.failRegistration()
        // Two more failures put the next automatic retry half an hour out.
        backend.answer(REGISTER, 401, 401)
        repeat(2) {
            service.onSignedIn()
            service.awaitRegistrations()
        }

        // The registration this makes fails as well.
        backend.answer(REGISTER, 401)
        service.updateCloudSyncEnabled(true)
        service.awaitRegistrations()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        service.retryRegistrationIfDue { "fcm-1" }
        service.awaitRegistrations()

        assertEquals("after one failure the retry is due in 30 s", 5, backend.received(REGISTER).size)
    }

    private companion object {
        const val REGISTER = "POST /devices/register"
        const val PREFERENCES = "PATCH /devices/preferences"
    }
}

/**
 * Answers every request in place of the backend, which nothing reaches: the interceptor never
 * calls proceed(). Each endpoint answers 200 unless a test queued another status for it.
 */
private class FakeBackend {
    class Received(val endpoint: String, val body: String)

    private val received = CopyOnWriteArrayList<Received>()
    private val queued = ConcurrentHashMap<String, ConcurrentLinkedQueue<Int>>()

    val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain -> answer(chain.request()) }
        .build()

    fun answer(endpoint: String, vararg codes: Int) {
        codes.forEach { queue(endpoint).add(it) }
    }

    fun received(endpoint: String): List<Received> = received.filter { it.endpoint == endpoint }

    private fun queue(endpoint: String) = queued.getOrPut(endpoint) { ConcurrentLinkedQueue() }

    private fun answer(request: Request): Response {
        val path = request.url.encodedPath
        val endpoint = request.method + " " + when {
            path.endsWith("/devices/anonymous") -> "/devices/anonymous"
            path.endsWith("/devices/register") -> "/devices/register"
            // Its path holds the install's device id.
            path.endsWith("/preferences") -> "/devices/preferences"
            else -> path
        }
        val body = request.body?.let { Buffer().also(it::writeTo).readUtf8() }.orEmpty()
        received += Received(endpoint, body)
        val code = queued[endpoint]?.poll() ?: 200
        val reply = if (code in 200..299) {
            """{"device_id":"device-1","push_token_id":1,"cloud_sync_enabled":true}"""
        } else {
            """{"detail":"refused"}"""
        }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .body(reply.toResponseBody("application/json".toMediaType()))
            .build()
    }
}
