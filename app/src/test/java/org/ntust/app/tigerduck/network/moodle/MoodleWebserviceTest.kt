package org.ntust.app.tigerduck.network.moodle

import kotlinx.coroutines.cancelAndJoin
import okio.buffer
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.network.MoodleWebserviceError

/** The one call helper every Moodle request goes through: its error mapping and its token handling. */
class MoodleWebserviceTest {

    private class Tokens(var stored: String?, private val fresh: String = "fresh") : MoodleWebservice.TokenSource {
        val renewed = mutableListOf<String?>()
        override fun stored(): String? = stored
        override suspend fun renew(rejected: String?): String {
            renewed += rejected
            stored = fresh
            return fresh
        }
    }

    private val sent = mutableListOf<Request>()

    private fun answering(vararg answers: Triple<Int, String, String?>): OkHttpClient {
        var next = 0
        return OkHttpClient.Builder().addInterceptor { chain ->
            sent += chain.request()
            val (code, body, retryAfter) = answers[minOf(next++, answers.lastIndex)]
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("")
                .apply { if (retryAfter != null) header("Retry-After", retryAfter) }
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
    }

    private fun ok(body: String) = Triple(200, body, null)

    @Test
    fun `Moodle's error in a 200 throws its own case`() {
        assertThrows(MoodleWebserviceError.SiteMaintenance::class.java) {
            MoodleWebservice.checkAnswer(200, null, """{"errorcode":"sitemaintenance","exception":"moodle_exception"}""")
        }
        assertThrows(MoodleWebserviceError.AccessDenied::class.java) {
            MoodleWebservice.checkAnswer(200, null, """{"errorcode":"accessexception"}""")
        }
        assertThrows(MoodleWebserviceError.InvalidToken::class.java) {
            MoodleWebservice.checkAnswer(200, null, """{"errorcode":"invalidtoken"}""")
        }
    }

    @Test
    fun `the maintenance page is a 503 with Retry-After`() {
        assertThrows(MoodleWebserviceError.SiteMaintenance::class.java) {
            MoodleWebservice.checkAnswer(503, "120", "<html>maintenance</html>")
        }
        val plain = assertThrows(MoodleWebserviceError.HttpStatus::class.java) {
            MoodleWebservice.checkAnswer(503, null, "")
        }
        assertEquals(503, plain.code)
    }

    @Test
    fun `a call posts its parameters as a form under the token`() = runTest {
        val service = MoodleWebservice({ answering(ok("""{"status":true}""")) }, Tokens("t1"))

        service.call("mod_forum_view_forum_discussion", "t1", listOf("discussionid" to "42"))

        val request = sent.single()
        assertEquals("POST", request.method)
        assertEquals("mod_forum_view_forum_discussion", request.url.queryParameter("wsfunction"))
        assertEquals("t1", request.url.queryParameter("wstoken"))
        val form = request.body as FormBody
        assertEquals("discussionid" to "42", form.name(0) to form.value(0))
    }

    @Test
    fun `an access error is tried once more with the same token and starts no renewal`() = runTest {
        val tokens = Tokens("t1")
        val service = MoodleWebservice({ answering(ok("{}")) }, tokens)
        val used = mutableListOf<String>()

        val error = runCatching {
            service.withToken<Unit> { token ->
                used += token
                throw MoodleWebserviceError.AccessDenied()
            }
        }.exceptionOrNull()

        assertTrue(error is MoodleWebserviceError.AccessDenied)
        assertEquals(listOf("t1", "t1"), used)
        assertTrue(tokens.renewed.isEmpty())
    }

    @Test
    fun `an expired token answers access then invalid, and is renewed once`() = runTest {
        val tokens = Tokens("old")
        val service = MoodleWebservice({ answering(ok("{}")) }, tokens)
        val used = mutableListOf<String>()

        val result = service.withToken { token ->
            used += token
            when (used.size) {
                1 -> throw MoodleWebserviceError.AccessDenied()
                2 -> throw MoodleWebserviceError.InvalidToken()
                else -> "answer with $token"
            }
        }

        assertEquals("answer with fresh", result)
        assertEquals(listOf("old", "old", "fresh"), used)
        assertEquals(listOf<String?>("old"), tokens.renewed)
    }

    @Test
    fun `without a stored token one is obtained first`() = runTest {
        val tokens = Tokens(stored = null)
        val service = MoodleWebservice({ answering(ok("{}")) }, tokens)

        val result = service.withToken { it }

        assertEquals("fresh", result)
        assertEquals(listOf<String?>(null), tokens.renewed)
    }

    @Test
    fun `site info is asked once per token`() = runTest {
        val client = answering(
            ok("""{"userid":7,"functions":[{"name":"mod_forum_get_forums_by_courses"}],"userprivateaccesskey":"key"}"""),
        )
        val service = MoodleWebservice({ client }, Tokens("t1"))

        val first = service.siteInfo("t1")
        val second = service.siteInfo("t1")

        assertEquals(7, first.userId)
        assertEquals(setOf("mod_forum_get_forums_by_courses"), first.functions)
        assertEquals("key", first.privateAccessKey)
        assertEquals(first, second)
        assertEquals(1, sent.size)

        service.siteInfo("t2")
        assertEquals("another token asks again", 2, sent.size)
    }

    @Test
    fun `a downloaded file must be the file Moodle listed`() {
        MoodleWebservice.checkFile(200, "application/pdf; name=a.pdf", "application/pdf")
        MoodleWebservice.checkFile(200, "application/octet-stream", null)
        assertThrows("a sign-in page in its place", MoodleWebserviceError.MalformedResponse::class.java) {
            MoodleWebservice.checkFile(200, "text/html", "application/pdf")
        }
        assertThrows(MoodleWebserviceError.HttpStatus::class.java) {
            MoodleWebservice.checkFile(404, "text/html", "application/pdf")
        }
    }

    private fun downloading(body: okio.Buffer, type: String, length: Long = -1L) = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("")
            .body(object : okhttp3.ResponseBody() {
                override fun contentType() = type.toMediaType()
                override fun contentLength() = length
                override fun source(): okio.BufferedSource = body
            })
            .build()
    }.build()

    private val fileRequest = Request.Builder().url("https://moodle2.ntust.edu.tw/tokenpluginfile.php/k/1/a.pdf").build()

    @get:org.junit.Rule
    val tmp = org.junit.rules.TemporaryFolder()

    @Test
    fun `a dead token in a download of unknown length is the token error, so a renewal starts`() = runTest {
        val client = downloading(okio.Buffer().writeUtf8("""{"errorcode":"invalidtoken","exception":"moodle_exception"}"""), "application/json")
        val target = tmp.newFile()

        val error = runCatching {
            MoodleWebservice({ client }, Tokens("t1")).downloadTo(fileRequest, target, "application/pdf", maxBytes = 1_000)
        }.exceptionOrNull()

        assertTrue(error is MoodleWebserviceError.InvalidToken)
    }

    @Test
    fun `a JSON file is kept whole`() = runTest {
        val json = """{"week":1,"topics":["lists","trees"]}"""
        val client = downloading(okio.Buffer().writeUtf8(json), "application/json; charset=utf-8")
        val target = tmp.newFile()

        MoodleWebservice({ client }, Tokens("t1")).downloadTo(fileRequest, target, "application/json", maxBytes = 1_000)

        assertEquals(json, target.readText())
    }

    @Test
    fun `a download larger than allowed stops`() = runTest {
        val client = downloading(okio.Buffer().write(ByteArray(5_000)), "application/pdf")
        val target = tmp.newFile()

        val error = runCatching {
            MoodleWebservice({ client }, Tokens("t1")).downloadTo(fileRequest, target, "application/pdf", maxBytes = 1_000)
        }.exceptionOrNull()

        assertTrue(error is MoodleWebserviceError.MalformedResponse)
    }

    @Test
    fun `cancelling a download cancels its request`() = kotlinx.coroutines.runBlocking {
        var call: okhttp3.Call? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            call = chain.call()
            val endless = object : okio.Source {
                override fun read(sink: okio.Buffer, byteCount: Long): Long {
                    // A slow network: a byte at a time until the call is cancelled.
                    if (chain.call().isCanceled()) throw java.io.IOException("Canceled")
                    Thread.sleep(5)
                    sink.writeByte(0)
                    return 1
                }
                override fun timeout() = okio.Timeout.NONE
                override fun close() = Unit
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("")
                .body(object : okhttp3.ResponseBody() {
                    override fun contentType() = "application/pdf".toMediaType()
                    override fun contentLength() = -1L
                    override fun source(): okio.BufferedSource = endless.buffer()
                })
                .build()
        }.build()
        val target = tmp.newFile()

        val job = launch(kotlinx.coroutines.Dispatchers.Default) {
            MoodleWebservice({ client }, Tokens("t1")).downloadTo(fileRequest, target, "application/pdf", maxBytes = Long.MAX_VALUE)
        }
        while (call == null) kotlinx.coroutines.delay(10)
        kotlinx.coroutines.delay(50)
        kotlinx.coroutines.withTimeout(2_000) { job.cancelAndJoin() }

        assertTrue(call!!.isCanceled())
    }
}
