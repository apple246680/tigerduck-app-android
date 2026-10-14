package org.ntust.app.tigerduck.network.moodle

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/** Moodle's answers are read off the thread that asked: HTML parsing a page of posts is not for the main thread. */
class LiveMoodleCourseApiTest {

    private class Recording : CoroutineDispatcher() {
        var used = 0
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            used++
            block.run()
        }
    }

    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        val function = chain.request().url.queryParameter("wsfunction")
        val body = when (function) {
            "mod_forum_get_forums_by_courses" ->
                """[{"id":1,"course":5,"type":"news","name":"<b>News</b>","cmid":9,"numdiscussions":2}]"""
            else -> "{}"
        }
        Response.Builder()
            .request(chain.request())
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
    fun `an answer is read on the background dispatcher`() = runTest {
        val parse = Recording()
        val api = LiveMoodleCourseApi(MoodleWebservice({ client }, tokens), parse)

        val forums = api.forums(listOf(5))

        assertEquals("News", forums.single().name)
        assertTrue(parse.used > 0)
    }
}
