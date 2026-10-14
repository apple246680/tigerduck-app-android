package org.ntust.app.tigerduck.network

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** What [MoodleService.requestSubmissionStatus] sends, and what it makes of the answer. */
class MoodleSubmissionStatusRequestTest {

    private val url =
        "https://moodle2.ntust.edu.tw/webservice/rest/server.php?wsfunction=mod_assign_get_submission_status"

    private val sent = mutableListOf<Request>()

    // Answers in place of the network: the interceptor never calls proceed().
    private fun answering(body: String, code: Int = 200) =
        OkHttpClient.Builder().addInterceptor { chain ->
            sent += chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()

    private fun sentForm(): List<Pair<String, String>> {
        val form = sent.single().body as FormBody
        return (0 until form.size).map { form.name(it) to form.value(it) }
    }

    @Test
    fun `without a user id it asks about the token's own account`() {
        MoodleService.requestSubmissionStatus(answering("""{"warnings":[]}"""), url, 20977, userId = null)

        assertEquals(listOf("assignid" to "20977"), sentForm())
    }

    @Test
    fun `with a user id it asks about that user`() {
        MoodleService.requestSubmissionStatus(answering("""{"warnings":[]}"""), url, 20977, userId = 16045)

        assertEquals(listOf("assignid" to "20977", "userid" to "16045"), sentForm())
    }

    @Test
    fun `an HTTP error throws`() {
        assertThrows(MoodleWebserviceError.HttpStatus::class.java) {
            MoodleService.requestSubmissionStatus(answering("", code = 503), url, 20977, userId = null)
        }
    }

    @Test
    fun `whether the student can still submit is read`() {
        val attempt = MoodleService.requestSubmissionStatus(
            answering(
                """
                {"lastattempt":{
                  "submission":{"id":48230,"userid":16045,"attemptnumber":0,
                    "timecreated":1760000000,"timemodified":1760000000,"status":"new",
                    "groupid":0,"assignment":20977,"latest":1,"plugins":[]},
                  "submissiongroupmemberswhoneedtosubmit":[],
                  "submissionsenabled":false,"locked":true,"graded":false,"canedit":false,
                  "caneditowner":false,"cansubmit":false,"extensionduedate":1760486400,
                  "timelimit":0,"blindmarking":false,"gradingstatus":"notgraded","usergroups":[]},
                 "assignmentdata":{},"warnings":[]}
                """.trimIndent()
            ),
            url,
            20977,
            userId = null,
        ).lastattempt

        assertEquals(false, attempt?.submissionsenabled)
        assertEquals(true, attempt?.locked)
        assertEquals(1_760_486_400L, attempt?.extensionduedate)
    }

    @Test
    fun `no extension decodes as none`() {
        val attempt = MoodleService.decodeSubmissionStatus(
            """{"lastattempt":{"submissionsenabled":true,"locked":false,"extensionduedate":null}}"""
        ).lastattempt

        assertEquals(null, attempt?.extensionduedate)
    }
}
