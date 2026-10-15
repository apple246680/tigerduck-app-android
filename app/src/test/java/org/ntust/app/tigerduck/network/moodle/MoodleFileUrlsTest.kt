package org.ntust.app.tigerduck.network.moodle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoodleFileUrlsTest {

    private val file = "https://moodle2.ntust.edu.tw/webservice/pluginfile.php/12/mod_forum/attachment/34/Week%201.pdf?forcedownload=1"

    @Test
    fun `with the private access key the file goes under tokenpluginfile`() {
        assertEquals(
            "https://moodle2.ntust.edu.tw/tokenpluginfile.php/abc/12/mod_forum/attachment/34/Week%201.pdf?forcedownload=1",
            MoodleFileUrls.downloadUrl(file, accessKey = "abc", token = "tok"),
        )
    }

    @Test
    fun `without the key the token goes on the web-service path`() {
        assertEquals(
            "https://moodle2.ntust.edu.tw/webservice/pluginfile.php/12/mod_forum/attachment/34/Week%201.pdf?forcedownload=1&token=tok",
            MoodleFileUrls.downloadUrl(file, accessKey = null, token = "tok"),
        )
    }

    @Test
    fun `a token already in the link is not sent twice`() {
        assertEquals(
            "https://moodle2.ntust.edu.tw/webservice/pluginfile.php/1/a.pdf?token=tok",
            MoodleFileUrls.downloadUrl(
                "https://moodle2.ntust.edu.tw/pluginfile.php/1/a.pdf?token=old",
                accessKey = "",
                token = "tok",
            ),
        )
    }

    @Test
    fun `no key ever goes to another site or path`() {
        assertNull(MoodleFileUrls.downloadUrl("https://example.com/pluginfile.php/1/a.pdf", "abc", "tok"))
        assertNull(MoodleFileUrls.downloadUrl("http://moodle2.ntust.edu.tw/pluginfile.php/1/a.pdf", "abc", "tok"))
        assertNull(MoodleFileUrls.downloadUrl("https://moodle2.ntust.edu.tw/course/view.php?id=1", "abc", "tok"))
    }
}
