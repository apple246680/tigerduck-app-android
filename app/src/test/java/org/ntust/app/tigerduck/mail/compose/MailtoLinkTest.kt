package org.ntust.app.tigerduck.mail.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MailtoLinkTest {
    @Test
    fun `the address, and the subject and body when there are any`() {
        assertEquals(MailtoLink.Draft("office@mail.ntust.edu.tw", "", "", ""), MailtoLink.parse("mailto:office@mail.ntust.edu.tw"))
        assertEquals(
            MailtoLink.Draft("a@x.tw", "", "期中考 time", "Hi,\nthanks+1"),
            MailtoLink.parse("MAILTO:a@x.tw?subject=%E6%9C%9F%E4%B8%AD%E8%80%83%20time&body=Hi%2C%0D%0Athanks+1"),
        )
    }

    @Test
    fun `several addresses, in the path and in to and cc`() {
        assertEquals(
            MailtoLink.Draft("a@x.tw, b@x.tw, c@x.tw", "d@x.tw", "", ""),
            MailtoLink.parse("mailto:a@x.tw,b@x.tw?to=c@x.tw&cc=d@x.tw"),
        )
    }

    @Test
    fun `a hidden recipient is never filled in for the sender to miss`() {
        assertEquals(MailtoLink.Draft("a@x.tw", "", "", ""), MailtoLink.parse("mailto:a@x.tw?bcc=watcher@evil.example"))
    }

    @Test
    fun `anything but a mailto link is not one`() {
        assertNull(MailtoLink.parse("https://mail.ntust.edu.tw"))
        assertNull(MailtoLink.parse(""))
    }
}
