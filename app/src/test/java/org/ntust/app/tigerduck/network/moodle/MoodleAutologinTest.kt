package org.ntust.app.tigerduck.network.moodle

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Moodle pages open signed in through a one-time key, as the Moodle app opens them, or as they are. */
@OptIn(ExperimentalCoroutinesApi::class)
class MoodleAutologinTest {

    private val page = "https://moodle2.ntust.edu.tw/course/view.php?id=5"
    private val key = MoodleAutologin.Key(userId = 7, value = "onetime", autologinUrl = "https://moodle2.ntust.edu.tw/admin/tool/mobile/autologin.php")

    private var requests = 0
    private var answer: suspend () -> MoodleAutologin.Key? = { key }
    private var generation = 0
    private var privateToken: String? = "private"
    private var nowMs = 1_000_000_000L

    private fun TestScope.autologin() = MoodleAutologin(
        keySource = {
            requests++
            answer()
        },
        scope = backgroundScope,
        now = { nowMs },
        signInGeneration = { generation },
        hasPrivateToken = { privateToken != null },
    )

    @Test
    fun `a Moodle page opens behind a one-time key`() = runTest {
        val opened = autologin().url(page).toHttpUrl()

        assertEquals("/admin/tool/mobile/autologin.php", opened.encodedPath)
        assertEquals("7", opened.queryParameter("userid"))
        assertEquals("onetime", opened.queryParameter("key"))
        assertEquals(page, opened.queryParameter("urltogo"))
    }

    @Test
    fun `a page off the Moodle site or not over https asks for no key`() = runTest {
        val autologin = autologin()

        assertEquals("https://example.com/a", autologin.url("https://example.com/a"))
        assertEquals("http://moodle2.ntust.edu.tw/a", autologin.url("http://moodle2.ntust.edu.tw/a"))
        assertEquals(0, requests)
    }

    @Test
    fun `Moodle gives one key in six minutes, so the next open within them goes as it is`() = runTest {
        val autologin = autologin()
        autologin.url(page)
        nowMs += 5 * 60_000L

        assertEquals(page, autologin.url(page))
        assertEquals(1, requests)

        nowMs += 61_000L
        assertTrue(autologin.url(page).contains("key=onetime"))
        assertEquals(2, requests)
    }

    @Test
    fun `a slow key opens the page as it is, and still counts once it comes`() = runTest {
        val gate = CompletableDeferred<Unit>()
        answer = {
            gate.await()
            key
        }
        val autologin = autologin()

        assertEquals(page, autologin.url(page))
        // An open while the request is out asks for none of its own.
        val meanwhile = backgroundScope.async { autologin.url(page) }
        advanceTimeBy(MoodleAutologin.KEY_WAIT_MS + 1)
        assertEquals(page, meanwhile.await())
        gate.complete(Unit)
        runCurrent()

        assertEquals(page, autologin.url(page))
        assertEquals(1, requests)
    }

    @Test
    fun `a key that arrives after a sign-out is dropped`() = runTest {
        answer = {
            generation++
            key
        }

        assertEquals(page, autologin().url(page))
    }

    @Test
    fun `a key that lands once the private token is gone, as a sign-out clears it, is dropped`() = runTest {
        answer = {
            privateToken = null
            key
        }

        assertEquals(page, autologin().url(page))
    }

    @Test
    fun `a sign-out read at once drops a key that lands after it, though nothing has heard of it yet`() = runTest {
        val gate = CompletableDeferred<Unit>()
        answer = {
            gate.await()
            key
        }
        val autologin = autologin()
        val opening = backgroundScope.async { autologin.url(page) }
        runCurrent()
        // The sign-out moves the generation where it runs, before any collector sees it.
        generation++
        gate.complete(Unit)

        assertEquals(page, opening.await())
    }

    @Test
    fun `a failed request opens the page as it is and lets the next open ask again`() = runTest {
        answer = { throw java.io.IOException("offline") }
        val autologin = autologin()

        assertEquals(page, autologin.url(page))
        answer = { key }
        assertTrue(autologin.url(page).contains("key=onetime"))
    }

    @Test
    fun `a key whose sign-in link leaves the Moodle site is never used`() = runTest {
        answer = { key.copy(autologinUrl = "https://evil.example/autologin.php") }

        assertEquals(page, autologin().url(page))
    }

    // --- the request ---

    private var sentBody: String? = null
    private var sentUrl: okhttp3.HttpUrl? = null

    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val body = when (request.url.queryParameter("wsfunction")) {
            "core_webservice_get_site_info" -> """{"userid":7,"functions":[]}"""
            MoodleAutologin.KEY_FUNCTION -> {
                sentUrl = request.url
                sentBody = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                """{"key":"onetime","autologinurl":"https://moodle2.ntust.edu.tw/admin/tool/mobile/autologin.php"}"""
            }
            else -> "{}"
        }
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }.build()

    private val tokens = object : MoodleWebservice.TokenSource {
        override fun stored() = "t1"
        override suspend fun renew(rejected: String?) = "t1"
    }

    @Test
    fun `the private token goes in the request body, never its address`() = runTest {
        val got = MoodleAutologin.requestKey(MoodleWebservice({ client }, tokens)) { "private" }

        assertEquals(key, got)
        assertEquals("privatetoken=private", sentBody)
        assertFalse(sentUrl.toString().contains("private"))
    }

    @Test
    fun `without a private token nothing is asked`() = runTest {
        assertNull(MoodleAutologin.requestKey(MoodleWebservice({ client }, tokens)) { null })
        assertNull(sentBody)
    }
}
