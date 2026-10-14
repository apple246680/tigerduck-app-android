package org.ntust.app.tigerduck.network.moodle

import org.junit.Assert.assertEquals
import org.junit.Test

class MoodleHtmlTextTest {

    @Test
    fun `plain text runs blocks and breaks together with single spaces`() {
        assertEquals(
            "Title First line second line Item",
            MoodleHtmlText.plainText("<h3>Title</h3><p>First line<br>second&nbsp;line</p><ul><li>Item</li></ul><script>x()</script>"),
        )
    }

    @Test
    fun `markdown keeps structure and escapes the text`() {
        val md = MoodleHtmlText.markdown(
            "<h2>Exam</h2><p>Bring a <strong>pen</strong> and *paper*.</p><ol><li>Part A</li><li>Part B</li></ol>" +
                "<blockquote><p>Quote</p></blockquote>",
        )
        assertEquals(
            "## Exam\n\nBring a **pen** and \\*paper\\*.\n\n1. Part A\n2. Part B\n\n> Quote",
            md,
        )
    }

    @Test
    fun `links resolve against the site and only safe schemes stay links`() {
        assertEquals(
            "[Notes](<https://moodle2.ntust.edu.tw/mod/page/view.php?id=1>) and run",
            MoodleHtmlText.markdown("""<p><a href="/mod/page/view.php?id=1">Notes</a> and <a href="javascript:alert(1)">run</a></p>"""),
        )
        assertEquals(
            "[a@b.tw](<mailto:a@b.tw>)",
            MoodleHtmlText.markdown("""<a href="mailto:a@b.tw">a@b.tw</a>"""),
        )
    }

    @Test
    fun `an image becomes a link to it`() {
        assertEquals(
            "[chart](<https://moodle2.ntust.edu.tw/pluginfile.php/1/chart.png>)",
            MoodleHtmlText.markdown("""<img src="https://moodle2.ntust.edu.tw/pluginfile.php/1/chart.png" alt="chart">"""),
        )
    }

    @Test
    fun `a line that would read as a list is escaped`() {
        assertEquals("1\\. not a list", MoodleHtmlText.markdown("<p>1. not a list</p>"))
        assertEquals("\\- not a bullet", MoodleHtmlText.markdown("<p>- not a bullet</p>"))
    }
}
