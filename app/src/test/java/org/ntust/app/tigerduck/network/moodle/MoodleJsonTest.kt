package org.ntust.app.tigerduck.network.moodle

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.network.MoodleWebserviceError
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseSummary

/** Moodle's answers as the course screens read them. */
class MoodleJsonTest {

    private fun json(text: String) = JsonParser.parseString(text)

    @Test
    fun `forums keep the counts and a plain-text intro`() {
        val forums = MoodleJson.forums(
            json(
                """
                [{"id":11,"course":5,"type":"news","name":"Announcements","cmid":101,
                  "numdiscussions":3,"unreadpostscount":1,"intro":"<p>From the <b>teacher</b></p>"},
                 {"id":12,"course":5,"type":"general","name":"Q&amp;A","cmid":102,"intro":""},
                 {"name":"no id"}]
                """,
            ),
        )
        assertEquals(2, forums.size)
        assertTrue(forums[0].isNews)
        assertEquals(3, forums[0].discussionCount)
        assertEquals(1, forums[0].unreadPostCount)
        assertEquals("From the teacher", forums[0].intro)
        assertEquals("Q&A", forums[1].name)
        assertNull("an empty intro reads as none", forums[1].intro)
    }

    @Test
    fun `a discussion keeps its message, a preview, its attachments and author`() {
        val discussions = MoodleJson.discussions(
            json(
                """
                {"discussions":[{"id":900,"discussion":77,"name":"Midterm","message":"<p>Room <b>TR-313</b></p>",
                  "created":1760000000,"timemodified":1760003600,"pinned":true,"numunread":"2","numreplies":0,
                  "userid":501,"userfullname":"T0001 @ Wang",
                  "attachments":[{"filename":"a.pdf","filesize":10,"fileurl":"https://moodle2.ntust.edu.tw/webservice/pluginfile.php/1/a.pdf","mimetype":"application/pdf"},
                                 {"filesize":1}]}],
                 "warnings":[]}
                """,
            ),
        )
        val d = discussions.single()
        assertEquals(77, d.id)
        assertEquals(900, d.postId)
        assertEquals("Room TR-313", d.preview)
        assertTrue(d.isPinned)
        assertEquals("a number sent as text still counts", 2, d.unreadCount)
        assertEquals(501, d.authorId)
        assertEquals("a.pdf", d.attachments.single().name)
    }

    @Test
    fun `grade items leave out what is not graded and keep feedback as Markdown`() {
        val items = MoodleJson.gradeItems(
            json(
                """
                {"usergrades":[{"userid":7,"gradeitems":[
                  {"id":1,"itemname":"HW1","itemtype":"mod","itemmodule":"assign","cmid":31,"graderaw":95.0,
                   "gradeformatted":"95.00","grademax":100,"feedback":"<p>Nice <em>work</em></p>"},
                  {"id":2,"itemname":"HW2","itemtype":"mod","itemmodule":"assign","graderaw":null,"gradeformatted":"-","grademax":100},
                  {"id":3,"itemname":"","itemtype":"course","graderaw":88.5,"gradeformatted":"88.50","grademax":100}
                ]},{"userid":8,"gradeitems":[]}]}
                """,
            ),
            userId = 7,
        )
        assertEquals(listOf(1, 2, 3), items.map { it.id })
        assertEquals("95.00", items[0].grade)
        assertEquals("Nice *work*", items[0].feedback)
        assertNull("an ungraded item has no grade", items[1].grade)
        assertTrue(items[2].isCourseTotal)
    }

    @Test
    fun `members keep only their own pictures and drop unreadable fields`() {
        val members = MoodleJson.members(
            json(
                """
                [{"id":1,"fullname":"B11300001 @ Amy","idnumber":" B11300001 ","department":"",
                  "email":"amy@mail.ntust.edu.tw","groups":[{"name":"Team <b>A</b>"}],
                  "roles":[{"roleid":5,"name":"","shortname":"student","sortorder":0}],
                  "profileimageurl":"https://moodle2.ntust.edu.tw/pluginfile.php/9/user/icon/moove/f1?rev=3",
                  "description":"<p>Hi</p>","interests":"tea, ,cats",
                  "customfields":[{"type":"text","name":"Nickname","value":"A"},{"type":"checkbox","name":"Agree","value":"1"}]},
                 {"id":2,"fullname":"Teacher","profileimageurl":"https://moodle2.ntust.edu.tw/theme/image.php/moove/core/1/u/f1"}]
                """,
            ),
        )
        val amy = members[0]
        assertEquals("B11300001", amy.idNumber)
        assertNull("a blank department reads as none", amy.department)
        assertEquals(listOf("Team A"), amy.groups)
        assertEquals("student", amy.roles.single().shortName)
        assertEquals(listOf("tea", "cats"), amy.interests)
        assertEquals("Nickname", amy.fields.single().name)
        assertTrue(amy.pictureUrl!!.contains("/user/icon/"))
        assertNull("Moodle's default picture is never downloaded", members[1].pictureUrl)
    }

    @Test
    fun `the summary of the course asked about, or none`() {
        val answer = json(
            """{"courses":[{"id":5,"fullname":"Data Structures","shortname":"DS","idnumber":"1151CS3005301",
                 "summary":"<p>Weekly <a href=\"/mod/page/view.php?id=1\">notes</a></p>"}]}""",
        )
        val summary = MoodleJson.summary(answer, courseId = 5)
        assertEquals("Weekly notes", summary.text)
        assertEquals(listOf("Data Structures", "DS", "1151CS3005301"), summary.names)
        assertEquals(MoodleCourseSummary.EMPTY, MoodleJson.summary(answer, courseId = 6))
    }

    @Test
    fun `site info without a user id is malformed`() {
        assertThrows(MoodleWebserviceError.MalformedResponse::class.java) {
            MoodleJson.siteInfo(json("""{"functions":[]}"""))
        }
    }

    @Test
    fun `only pictures under the site's user icons are a member's own`() {
        assertTrue(MoodleJson.isOwnPicture("https://moodle2.ntust.edu.tw/webservice/pluginfile.php/9/user/icon/f1"))
        assertFalse(MoodleJson.isOwnPicture("https://evil.example/pluginfile.php/9/user/icon/f1"))
        assertFalse(MoodleJson.isOwnPicture("http://moodle2.ntust.edu.tw/pluginfile.php/9/user/icon/f1"))
    }
}
