package org.ntust.app.tigerduck.auth

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.auth.AuthTokenManager.SignInAttempt
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.data.preferences.V3TokenStore.Companion.KEY_ACCESS_TOKEN
import org.ntust.app.tigerduck.data.preferences.V3TokenStore.Companion.KEY_EXPIRES_AT
import org.ntust.app.tigerduck.data.preferences.V3TokenStore.Companion.KEY_REFRESH_TOKEN
import org.ntust.app.tigerduck.network.resolveAnnouncementEndpoint
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.Closeable
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlin.concurrent.thread

/**
 * How [AuthTokenManager] keeps the v3 pair through a refresh. The backend
 * rotates the refresh token on every refresh, answers the old one once more
 * inside a 60 s grace, and revokes every session of the device when the old
 * one comes back after that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AuthTokenManagerRefreshTest {

    private lateinit var context: Application
    private lateinit var file: SavesRecorder
    private lateinit var tokens: V3TokenStore
    private var clock = 1_800_000_000_000L

    /** The refresh tokens the fake backend received, in order. */
    private val sentRefreshTokens = mutableListOf<String>()
    private var relogins = 0

    /** Runs as each refresh reaches the fake backend, before its reply. */
    private var onRefreshSent: () -> Unit = {}

    /** Runs as each sign-in reaches the fake backend, before its reply. */
    private var onLoginSent: () -> Unit = {}

    /** The requests the client of [networkManager] began. */
    private val calls = AtomicInteger()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        storePair(expiresAt = 0L)
    }

    /** Puts "access-1" and "refresh-1" on disk and starts counting saves afresh. */
    private fun storePair(expiresAt: Long) {
        val prefs = context.getSharedPreferences("v3-tokens-test", Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, "access-1")
            .putString(KEY_REFRESH_TOKEN, "refresh-1")
            .putLong(KEY_EXPIRES_AT, expiresAt)
            .commit()
        file = SavesRecorder(prefs)
        tokens = V3TokenStore(file)
        sentRefreshTokens.clear()
        relogins = 0
    }

    @Test
    fun `a refresh writes the new pair in one commit`() = runTest {
        val manager = manager(Reply.Pair("access-2", "refresh-2"))

        assertEquals("Bearer access-2", manager.authHeader())

        assertEquals(listOf(setOf(KEY_ACCESS_TOKEN, KEY_REFRESH_TOKEN, KEY_EXPIRES_AT)), file.commits)
        assertEquals(0, file.applies)
        assertEquals(listOf("refresh-1"), sentRefreshTokens)
        assertEquals("refresh-2", tokens.refreshToken)
    }

    @Test
    fun `a pair whose commit fails is still used`() = runTest {
        // SharedPreferences holds an edit in memory before it writes the file, and a disk
        // that refused this write would refuse a relogin's pair or a clear as well.
        file.failCommits = true
        val manager = manager(Reply.Pair("access-2", "refresh-2"))

        assertEquals("Bearer access-2", manager.authHeader())

        assertEquals(0, relogins)
        assertEquals("refresh-2", tokens.refreshToken)
    }

    @Test
    fun `a sign-in writes its pair in one commit`() = runTest {
        val manager = manager(Reply.Pair("access-2", "refresh-2"))

        manager.login("B00000000", moodleToken = "moodle", moodlePrivateToken = null)

        assertEquals(listOf(setOf(KEY_ACCESS_TOKEN, KEY_REFRESH_TOKEN, KEY_EXPIRES_AT)), file.commits)
        assertEquals(0, file.applies)
        assertEquals("refresh-2", tokens.refreshToken)
    }

    @Test
    fun `a refresh whose reply was lost is sent once more with the same token and keeps that pair`() = runTest {
        val manager = manager(Reply.Lost, Reply.Pair("access-2", "refresh-2"))

        assertEquals("Bearer access-2", manager.authHeader())

        assertEquals(listOf("refresh-1", "refresh-1"), sentRefreshTokens)
        assertEquals(0, relogins)
        assertEquals("refresh-2", tokens.refreshToken)
    }

    @Test
    fun `a second lost reply is not sent a third time`() = runTest {
        val manager = manager(Reply.Lost, Reply.Lost, Reply.Pair("access-2", "refresh-2"))

        assertNull(manager.authHeader())

        // The resend may have spent the backend's one grace: sign in again as before.
        assertEquals(listOf("refresh-1", "refresh-1"), sentRefreshTokens)
        assertEquals(1, relogins)
        assertNull(tokens.refreshToken)
    }

    @Test
    fun `a lost reply and then a 5xx signs in again`() = runTest {
        val manager = manager(Reply.Lost, Reply.Status(503))

        assertNull(manager.authHeader())

        // The first send may have rotated the token, and a later refresh would send it after the grace.
        assertEquals(listOf("refresh-1", "refresh-1"), sentRefreshTokens)
        assertEquals(1, relogins)
        assertNull(tokens.refreshToken)
    }

    @Test
    fun `a 429 or a 5xx keeps the pair without signing in again`() = runTest {
        for (code in listOf(429, 500, 502, 503, 504)) {
            storePair(expiresAt = 0L)
            val manager = manager(Reply.Status(code))

            assertNull("HTTP $code", manager.authHeader())

            assertEquals("HTTP $code", listOf("refresh-1"), sentRefreshTokens)
            assertEquals("HTTP $code", 0, relogins)
            assertEquals("HTTP $code", "access-1", tokens.accessToken)
            assertEquals("HTTP $code", "refresh-1", tokens.refreshToken)
        }
    }

    @Test
    fun `a 429 in the access token's last 30 s gives no header`() = runTest {
        // A token about to expire would draw a 401, and Home answers a 401 with a relogin.
        storePair(expiresAt = clock + 10_000)
        val manager = manager(Reply.Status(429))

        assertNull(manager.authHeader())
        assertNull(manager.authHeader())

        assertEquals(listOf("refresh-1"), sentRefreshTokens)
        assertEquals("refresh-1", tokens.refreshToken)
    }

    @Test
    fun `after a 429 or a 5xx the next refresh waits a minute and sends the kept token`() = runTest {
        val manager = manager(Reply.Status(503), Reply.Pair("access-2", "refresh-2"))
        assertNull(manager.authHeader())

        clock += 59_000
        assertNull(manager.authHeader())
        assertEquals(listOf("refresh-1"), sentRefreshTokens)

        clock += 1_000
        assertEquals("Bearer access-2", manager.authHeader())
        assertEquals(listOf("refresh-1", "refresh-1"), sentRefreshTokens)
        assertEquals("refresh-2", tokens.refreshToken)
    }

    @Test
    fun `a clock set back does not stretch the wait`() = runTest {
        val manager = manager(Reply.Status(429), Reply.Pair("access-2", "refresh-2"))
        assertNull(manager.authHeader())

        clock -= 3_600_000

        assertEquals("Bearer access-2", manager.authHeader())
    }

    @Test
    fun `a 401 signs in again as before`() = runTest {
        val manager = manager(Reply.Status(401), reloginSucceeds = true)

        assertEquals("Bearer access-relogin", manager.authHeader())

        assertEquals(listOf("refresh-1"), sentRefreshTokens)
        assertEquals(1, relogins)
    }

    @Test
    fun `a 401 whose sign-in fails drops the pair in one commit`() = runTest {
        val manager = manager(Reply.Status(401))

        assertNull(manager.authHeader())

        assertEquals(1, relogins)
        assertNull(tokens.refreshToken)
        assertEquals(listOf(setOf(KEY_ACCESS_TOKEN, KEY_REFRESH_TOKEN, KEY_EXPIRES_AT)), file.commits)
        assertEquals(0, file.applies)
    }

    @Test
    fun `a caller cancelled during the resend still signs in again`() = runTest {
        val manager = manager(Reply.Lost, Reply.Lost)
        lateinit var caller: Job
        onRefreshSent = { if (sentRefreshTokens.size == 2) caller.cancel() }

        caller = launch { manager.authHeader() }
        caller.join()

        // Left on disk, the token would go out a third time on the next refresh.
        assertEquals(1, relogins)
        assertNull(tokens.refreshToken)
    }

    @Test
    fun `a caller cancelled during the sign-in after a failed resend still drops the token`() = runTest {
        val manager = manager(Reply.Lost, Reply.Lost)
        lateinit var caller: Job
        manager.signInWithStoredAccount = {
            relogins++
            caller.cancel()
            delay(1_000)
            SignInAttempt.FAILED
        }

        caller = launch { manager.authHeader() }
        caller.join()

        assertEquals(1, relogins)
        assertNull(tokens.refreshToken)
    }

    @Test
    fun `OkHttp does not send a refresh again on its own`() = runTest {
        // The sign-in leaves its connection in the pool, and the refresh goes out on it. OkHttp
        // resends a request that failed on a pooled connection unless told not to.
        LoopbackBackend(listOf(pairJson("access-2", "refresh-2", expiresIn = 0))).use { backend ->
            val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = backend.baseUrl }
            val manager = AuthTokenManager(tokens, OkHttpClient(), prefs, "device-1", now = { clock })
            manager.login("B00000000", moodleToken = "moodle", moodlePrivateToken = null)

            assertNull(manager.authHeader())

            assertEquals(listOf("refresh-2", "refresh-2"), backend.refreshTokens)
        }
    }

    @Test
    fun `a refresh reply that comes back after a sign-out stores nothing`() = runTest {
        val manager = manager(Reply.Pair("access-2", "refresh-2"))
        onRefreshSent = { manager.logout() }

        assertNull(manager.authHeader())

        assertNull(tokens.accessToken)
        assertNull(tokens.refreshToken)
        // The sign-out's clear, and no save after it.
        assertEquals(1, file.commits.size)
        assertEquals(0, relogins)
    }

    @Test
    fun `a sign-in reply that comes back after a sign-out stores nothing`() = runTest {
        val manager = manager(Reply.Pair("access-2", "refresh-2"))
        onLoginSent = { manager.logout() }

        val failure = runCatching {
            manager.login("B00000000", moodleToken = "moodle", moodlePrivateToken = null)
        }.exceptionOrNull()

        assertTrue(failure is AuthTokenManager.AuthException)
        assertNull(tokens.refreshToken)
        assertEquals(1, file.commits.size)
    }

    @Test
    fun `a sign-in that read the credentials before a sign-out stores nothing`() = runTest {
        val manager = manager(Reply.Pair("access-2", "refresh-2"))
        // Read as AuthService reads it, before the credentials and the Moodle harvest.
        val signIn = manager.signInGeneration
        manager.logout()

        val failure = runCatching {
            manager.login(
                "B00000000", moodleToken = "moodle", moodlePrivateToken = null,
                signInGeneration = signIn,
            )
        }.exceptionOrNull()

        assertTrue(failure is AuthTokenManager.AuthException)
        assertNull(tokens.refreshToken)
    }

    @Test
    fun `a lost reply is not sent again after a sign-out`() = runTest {
        val manager = manager(Reply.Lost, Reply.Pair("access-2", "refresh-2"))
        onRefreshSent = { manager.logout() }

        assertNull(manager.authHeader())

        assertEquals(listOf("refresh-1"), sentRefreshTokens)
        assertEquals(0, relogins)
        assertNull(tokens.refreshToken)
    }

    @Test
    fun `a refresh turned down after a sign-out leaves the next sign-in alone`() = runTest {
        val manager = manager(Reply.Status(401))
        onRefreshSent = {
            manager.logout()
            // The next account signs in while the refresh is out.
            tokens.save("access-b", "refresh-b", clock + 900_000)
        }

        assertNull(manager.authHeader())

        assertEquals(0, relogins)
        assertEquals("refresh-b", tokens.refreshToken)
    }

    @Test
    fun `a sign-out during the sign-in after a refused refresh leaves the next account alone`() = runTest {
        for (signedIn in listOf(false, true)) {
            storePair(expiresAt = 0L)
            val manager = manager(Reply.Status(401))
            manager.signInWithStoredAccount = {
                relogins++
                manager.logout()
                // The next account signs in before this sign-in returns.
                tokens.save("access-b", "refresh-b", clock + 900_000)
                if (signedIn) SignInAttempt.SIGNED_IN else SignInAttempt.FAILED
            }

            // Neither its token for the old caller nor a clear of its pair.
            assertNull("signed in: $signedIn", manager.authHeader())

            assertEquals("signed in: $signedIn", 1, relogins)
            assertEquals("signed in: $signedIn", "refresh-b", tokens.refreshToken)
        }
    }

    @Test
    fun `an unknown host keeps the pair and pauses refreshes`() = runTest {
        // What the system resolver raises offline; OkHttp passes it on as it is.
        val manager = networkManager("http://backend.localhost/v3") {
            dns { host -> throw UnknownHostException(host) }
        }

        assertNull(manager.authHeader())
        clock += 59_000
        assertNull(manager.authHeader())

        assertEquals(1, calls.get())
        assertEquals(0, relogins)
        assertEquals("access-1", tokens.accessToken)
        assertEquals("refresh-1", tokens.refreshToken)
    }

    @Test
    fun `a refused connection keeps the pair and pauses refreshes`() = runTest {
        // Closed again at once, so nothing listens on the port and the connect is refused.
        val port = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { it.localPort }
        val manager = networkManager("http://127.0.0.1:$port/v3")

        assertNull(manager.authHeader())
        clock += 59_000
        assertNull(manager.authHeader())

        assertEquals(1, calls.get())
        assertEquals(0, relogins)
        assertEquals("access-1", tokens.accessToken)
        assertEquals("refresh-1", tokens.refreshToken)
    }

    @Test
    fun `no route to the host keeps the pair and pauses refreshes`() = runTest {
        val manager = networkManager("http://127.0.0.1:9/v3") { socketFactory(UnroutableSocketFactory) }

        assertNull(manager.authHeader())
        clock += 59_000
        assertNull(manager.authHeader())

        assertEquals(1, calls.get())
        assertEquals(0, relogins)
        assertEquals("access-1", tokens.accessToken)
        assertEquals("refresh-1", tokens.refreshToken)
    }

    @Test
    fun `a refresh that times out after it went out is sent once more`() = runTest {
        LoopbackBackend(emptyList(), holdUnanswered = true).use { backend ->
            val manager = networkManager(backend.baseUrl) { readTimeout(200, TimeUnit.MILLISECONDS) }

            assertNull(manager.authHeader())

            // The first send may have rotated the token: one resend, then a sign-in as before.
            assertEquals(listOf("refresh-1", "refresh-1"), backend.refreshTokens)
            assertEquals(1, relogins)
            assertNull(tokens.refreshToken)
        }
    }

    /**
     * A manager whose refreshes go through OkHttp's own DNS lookup, connect and
     * exchange, so a test meets the exception OkHttp raises itself.
     */
    private fun networkManager(baseUrl: String, configure: OkHttpClient.Builder.() -> Unit = {}): AuthTokenManager {
        val prefs = AppPreferences(context).apply { announcementApiBaseUrlOverride = baseUrl }
        // Never the real backend.
        assertTrue(resolveAnnouncementEndpoint(prefs).overrideApplied)
        val client = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) {
                    calls.incrementAndGet()
                }
            })
            .apply(configure)
            .build()
        return AuthTokenManager(tokens, client, prefs, "device-1", now = { clock }).apply {
            signInWithStoredAccount = {
                relogins++
                SignInAttempt.FAILED
            }
        }
    }

    // The sign-out's unregister refreshes the departing session, which is never stored

    private fun departing() = DepartingSession("access-1", "refresh-1", 0L, "http://127.0.0.1:9/v3")

    @Test
    fun `a departing refresh whose reply was lost is sent once more with the same token`() = runTest {
        val manager = manager(Reply.Lost, Reply.Pair("access-2", "refresh-2"))

        val reply = manager.refreshDeparting(departing())

        assertEquals("refresh-2", (reply as DepartingRefresh.Refreshed).session.refreshToken)
        assertEquals(listOf("refresh-1", "refresh-1"), sentRefreshTokens)
    }

    @Test
    fun `a departing refresh lost twice is given up, never sent again later`() = runTest {
        // Within its grace the backend answers a rotated token once more; any later send of it is
        // taken for reuse, which revokes the session and leaves the unregister nothing to go out as.
        val manager = manager(Reply.Lost, Reply.Lost)

        assertEquals(DepartingRefresh.Refused, manager.refreshDeparting(departing()))
        assertEquals(listOf("refresh-1", "refresh-1"), sentRefreshTokens)
    }

    @Test
    fun `a departing refresh the backend did not take is left for a later try`() = runTest {
        val manager = manager(Reply.Status(503))

        assertEquals(DepartingRefresh.Unavailable, manager.refreshDeparting(departing()))
    }

    // A session refresh still on its way at sign-out, with the same token as the departing one

    @Test
    fun `a departing refresh takes the pair of a refresh on its way at sign-out instead of sending again`() = runTest {
        val manager = manager(Reply.Pair("access-2", "refresh-2"), Reply.Pair("access-3", "refresh-3"))
        // As AuthService.logout() takes it: while the session's refresh is on its way.
        var departing: DepartingSession? = null
        onRefreshSent = {
            departing = manager.departingSession()
            manager.logout()
            onRefreshSent = {}
        }
        assertNull(manager.authHeader())

        val reply = manager.refreshDeparting(departing!!)

        assertEquals("refresh-2", (reply as DepartingRefresh.Refreshed).session.refreshToken)
        assertEquals(listOf("refresh-1"), sentRefreshTokens)
        assertNull("still nothing stored", tokens.refreshToken)
    }

    @Test
    fun `a departing refresh waits for the session refresh of its token still on its way`() = runBlocking {
        val manager = manager(Reply.Pair("access-2", "refresh-2"), Reply.Pair("access-3", "refresh-3"))
        val sent = CountDownLatch(1)
        val answer = CountDownLatch(1)
        var departing: DepartingSession? = null
        onRefreshSent = {
            departing = manager.departingSession()
            manager.logout()
            onRefreshSent = {}
            sent.countDown()
            answer.await(5, TimeUnit.SECONDS)
        }
        val sessionRefresh = async(Dispatchers.IO) { manager.authHeader() }
        assertTrue(sent.await(5, TimeUnit.SECONDS))

        val unregisterRefresh = async(Dispatchers.IO) { manager.refreshDeparting(departing!!) }
        Thread.sleep(300)
        answer.countDown()

        assertNull(sessionRefresh.await())
        assertEquals("refresh-2", (unregisterRefresh.await() as DepartingRefresh.Refreshed).session.refreshToken)
        assertEquals(listOf("refresh-1"), sentRefreshTokens)
    }

    @Test
    fun `a token the session sent twice without a reply is not sent a third time for the unregister`() = runTest {
        val manager = manager(Reply.Lost, Reply.Lost, Reply.Pair("access-3", "refresh-3"))
        val departing = manager.departingSession()!!
        // The resend's reply is lost as the account signs out.
        var sends = 0
        onRefreshSent = { if (++sends == 2) manager.logout() }
        assertNull(manager.authHeader())

        assertEquals(DepartingRefresh.Refused, manager.refreshDeparting(departing))
        assertEquals(listOf("refresh-1", "refresh-1"), sentRefreshTokens)
    }

    private fun manager(vararg replies: Reply, reloginSucceeds: Boolean = false) = AuthTokenManager(
        tokens = tokens,
        httpClient = backend(*replies),
        prefs = AppPreferences(context),
        deviceUuid = "device-1",
        now = { clock },
    ).apply {
        // Stands in for AuthService's automatic sign-in, which signs in through login().
        signInWithStoredAccount = {
            relogins++
            if (reloginSucceeds) tokens.save("access-relogin", "refresh-relogin", clock + 900_000)
            if (reloginSucceeds) SignInAttempt.SIGNED_IN else SignInAttempt.FAILED
        }
    }

    /** What the fake backend does with each request it gets, in order. */
    private sealed interface Reply {
        /** A new pair, as a rotation or a sign-in answers. */
        data class Pair(val access: String, val refresh: String) : Reply

        /** No reply reaches the phone, whether or not the backend rotated. */
        data object Lost : Reply

        data class Status(val code: Int) : Reply
    }

    // Answers in place of the network: the interceptor never calls proceed().
    private fun backend(vararg replies: Reply): OkHttpClient {
        val script = ArrayDeque(replies.asList())
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                if (request.url.encodedPath.endsWith("/auth/refresh")) {
                    val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                    sentRefreshTokens += JSONObject(body).getString("refresh_token")
                    onRefreshSent()
                } else if (request.url.encodedPath.endsWith("/auth/login")) {
                    onLoginSent()
                }
                when (val reply = script.removeFirst()) {
                    Reply.Lost -> throw SocketTimeoutException("timeout")
                    is Reply.Status -> answer(request, reply.code, """{"detail":"error"}""")
                    is Reply.Pair -> answer(request, 200, pairJson(reply.access, reply.refresh))
                }
            }
            .build()
    }

    private fun answer(request: Request, code: Int, body: String) = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()
}

private fun pairJson(access: String, refresh: String, expiresIn: Int = 900) =
    """{"access_token":"$access","refresh_token":"$refresh","expires_in":$expiresIn,""" +
        """"device_id":"device-1"}"""

/**
 * A plain-HTTP backend on the loopback address that answers each request with
 * the next of [replies]. Once they run out it closes the connection without a
 * reply, or with [holdUnanswered] keeps it open and never answers. It keeps a
 * connection open after each answer, so OkHttp pools it.
 */
private class LoopbackBackend(
    private val replies: List<String>,
    private val holdUnanswered: Boolean = false,
) : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val answered = AtomicInteger()

    /** The refresh tokens it received, in order. */
    val refreshTokens = CopyOnWriteArrayList<String>()
    val baseUrl = "http://127.0.0.1:${server.localPort}/v3"

    init {
        thread(isDaemon = true) {
            while (true) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                // A client that timed out may reset the connection under the read.
                thread(isDaemon = true) { runCatching { serve(socket) } }
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.use {
            // Latin-1 maps each byte to one char, so Content-Length counts chars too.
            val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val output = socket.getOutputStream()
            while (true) {
                val path = input.readLine()?.split(' ')?.getOrNull(1) ?: return
                var length = 0
                while (true) {
                    val header = input.readLine() ?: return
                    if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", ignoreCase = true)) {
                        length = header.substringAfter(':').trim().toInt()
                    }
                }
                val body = CharArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(body, read, length - read)
                    if (n < 0) return
                    read += n
                }
                if (path.endsWith("/auth/refresh")) {
                    refreshTokens += JSONObject(String(body)).getString("refresh_token")
                }
                val reply = replies.getOrNull(answered.getAndIncrement())
                    ?: if (holdUnanswered) continue else return
                val bytes = reply.toByteArray()
                output.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${bytes.size}\r\n\r\n").toByteArray() + bytes
                )
                output.flush()
            }
        }
    }

    override fun close() = server.close()
}

/** Hands out sockets whose connect fails as Android's does when the host is unreachable. */
private object UnroutableSocketFactory : SocketFactory() {
    override fun createSocket(): Socket = object : Socket() {
        override fun connect(endpoint: SocketAddress?, timeout: Int): Unit =
            throw NoRouteToHostException("Host unreachable")
    }

    override fun createSocket(host: String?, port: Int): Socket = unconnectedOnly()
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        unconnectedOnly()
    override fun createSocket(host: InetAddress?, port: Int): Socket = unconnectedOnly()
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        unconnectedOnly()

    // OkHttp asks for an unconnected socket and connects it itself.
    private fun unconnectedOnly(): Nothing = throw UnsupportedOperationException()
}

/** Records the keys of each committed edit, and how many edits were applied instead. */
private class SavesRecorder(private val inner: SharedPreferences) : SharedPreferences by inner {
    val commits = mutableListOf<Set<String>>()
    var applies = 0

    /** Reports each commit as failed, as a disk that refuses the write does, after the edit is in memory. */
    var failCommits = false

    override fun edit(): SharedPreferences.Editor = Editor(inner.edit())

    private inner class Editor(private val editor: SharedPreferences.Editor) :
        SharedPreferences.Editor by editor {
        private val keys = mutableSetOf<String>()

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            keys += key
            editor.putString(key, value)
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            keys += key
            editor.putLong(key, value)
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            keys += key
            editor.remove(key)
            return this
        }

        override fun commit(): Boolean {
            commits += keys.toSet()
            return editor.commit() && !failCommits
        }

        override fun apply() {
            applies++
            editor.apply()
        }
    }
}
