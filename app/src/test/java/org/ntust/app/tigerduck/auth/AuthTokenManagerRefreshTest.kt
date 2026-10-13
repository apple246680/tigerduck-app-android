package org.ntust.app.tigerduck.auth

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
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
import java.net.SocketTimeoutException

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

    /** The refresh tokens the fake backend received, in order. */
    private val sentRefreshTokens = mutableListOf<String>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("v3-tokens-test", Context.MODE_PRIVATE)
        // Expired, so the next authHeader() refreshes.
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, "access-1")
            .putString(KEY_REFRESH_TOKEN, "refresh-1")
            .putLong(KEY_EXPIRES_AT, 0L)
            .commit()
        file = SavesRecorder(prefs)
        tokens = V3TokenStore(file)
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

    private fun manager(vararg replies: Reply) = AuthTokenManager(
        tokens = tokens,
        httpClient = backend(*replies),
        prefs = AppPreferences(context),
        deviceUuid = "device-1",
    )

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
                }
                when (val reply = script.removeFirst()) {
                    Reply.Lost -> throw SocketTimeoutException("timeout")
                    is Reply.Status -> answer(request, reply.code, """{"detail":"error"}""")
                    is Reply.Pair -> answer(
                        request,
                        200,
                        """{"access_token":"${reply.access}","refresh_token":"${reply.refresh}",""" +
                            """"expires_in":900,"device_id":"device-1"}""",
                    )
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
