package org.ntust.app.tigerduck.mail

import org.junit.Assert.assertEquals
import org.junit.Test

class MailRoutesTest {
    @Test
    fun `folder names are percent-encoded in routes`() {
        assertEquals("schoolMail/message/INBOX/42", MailRoutes.message("INBOX", 42))
        assertEquals("schoolMail/message/Moodle%20%E8%AA%B2%E7%A8%8B/7", MailRoutes.message("Moodle 課程", 7))
        assertEquals("schoolMail/compose?mode=REPLY&folder=INBOX&uid=42", MailRoutes.compose(ComposeMode.REPLY, "INBOX", 42))
        assertEquals("schoolMail/compose?mode=NEW&folder=&uid=-1", MailRoutes.compose(ComposeMode.NEW))
    }

    @Test
    fun `a new message can name its recipient`() {
        assertEquals(
            "schoolMail/compose?mode=NEW&folder=&uid=-1&to=b11300001%40mail.ntust.edu.tw",
            MailRoutes.compose(ComposeMode.NEW, to = "b11300001@mail.ntust.edu.tw"),
        )
    }
}
