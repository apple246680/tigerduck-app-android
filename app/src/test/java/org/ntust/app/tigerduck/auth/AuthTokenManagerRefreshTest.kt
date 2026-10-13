package org.ntust.app.tigerduck.auth

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.data.preferences.V3TokenStore
import org.ntust.app.tigerduck.data.preferences.V3TokenStore.Companion.KEY_ACCESS_TOKEN
import org.ntust.app.tigerduck.data.preferences.V3TokenStore.Companion.KEY_EXPIRES_AT
import org.ntust.app.tigerduck.data.preferences.V3TokenStore.Companion.KEY_REFRESH_TOKEN
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
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
    fun `a sign-in writes its pair in one commit`() = runTest {
        val manager = manager(Reply.Pair("access-2", "refresh-2"))

        manager.login("B00000000", "password", moodleToken = "moodle", moodlePrivateToken = null)

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
    fun `an access token not yet expired is still used after a 429`() = runTest {
        storePair(expiresAt = clock + 10_000)
        val manager = manager(Reply.Status(429))

        assertEquals("Bearer access-1", manager.authHeader())

        assertEquals(listOf("refresh-1"), sentRefreshTokens)
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
        manager.onRefreshFailed = {
            relogins++
            caller.cancel()
            delay(1_000)
            false
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
            manager.login("B00000000", "password", moodleToken = "moodle", moodlePrivateToken = null)

            assertNull(manager.authHeader())

            assertEquals(listOf("refresh-2", "refresh-2"), backend.refreshTokens)
        }
    }

    private fun manager(vararg replies: Reply, reloginSucceeds: Boolean = false) = AuthTokenManager(
        tokens = tokens,
        httpClient = backend(*replies),
        prefs = AppPreferences(context),
        deviceUuid = "device-1",
        now = { clock },
    ).apply {
        // Stands in for AuthService.attemptRelogin, which signs in through login().
        onRefreshFailed = {
            relogins++
            if (reloginSucceeds) tokens.save("access-relogin", "refresh-relogin", clock + 900_000)
            reloginSucceeds
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
 * the next of [replies] and, once they run out, closes the connection without
 * a reply. It keeps a connection open after each answer, so OkHttp pools it.
 */
private class LoopbackBackend(private val replies: List<String>) : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val answered = AtomicInteger()

    /** The refresh tokens it received, in order. */
    val refreshTokens = CopyOnWriteArrayList<String>()
    val baseUrl = "http://127.0.0.1:${server.localPort}/v3"

    init {
        thread(isDaemon = true) {
            while (true) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { serve(socket) }
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
                val reply = replies.getOrNull(answered.getAndIncrement()) ?: return
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

/** Records the keys of each committed edit, and how many edits were applied instead. */
private class SavesRecorder(private val inner: SharedPreferences) : SharedPreferences by inner {
    val commits = mutableListOf<Set<String>>()
    var applies = 0

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
            return editor.commit()
        }

        override fun apply() {
            applies++
            editor.apply()
        }
    }
}
