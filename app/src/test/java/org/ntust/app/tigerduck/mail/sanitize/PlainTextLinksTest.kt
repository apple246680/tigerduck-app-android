package org.ntust.app.tigerduck.mail.sanitize

import org.junit.Assert.assertEquals
import org.junit.Test

class PlainTextLinksTest {
    private fun links(text: String) = PlainTextLinks.find(text).map { text.substring(it.start, it.end) to it.href }

    @Test
    fun `an http or https URL is a link, ending where the URL characters do`() {
        assertEquals(
            listOf("https://www.lib.ntust.edu.tw/hours?x=1" to "https://www.lib.ntust.edu.tw/hours?x=1"),
            links("詳見https://www.lib.ntust.edu.tw/hours?x=1。謝謝"),
        )
        assertEquals(listOf("http://a.example" to "http://a.example"), links("Go to http://a.example now"))
    }

    @Test
    fun `punctuation that ends the sentence is not part of the link`() {
        assertEquals(listOf("https://a.example/p" to "https://a.example/p"), links("See https://a.example/p."))
        assertEquals(listOf("https://a.example" to "https://a.example"), links("(see https://a.example)"))
        assertEquals(listOf("https://a.example/x_(y)" to "https://a.example/x_(y)"), links("at https://a.example/x_(y)!"))
    }

    @Test
    fun `addresses become mailto links, written out or bare`() {
        assertEquals(
            listOf("library@mail.ntust.edu.tw" to "mailto:library@mail.ntust.edu.tw"),
            links("請寄信至library@mail.ntust.edu.tw。"),
        )
        assertEquals(
            listOf("mailto:a@b.example?subject=Hi" to "mailto:a@b.example?subject=Hi"),
            links("Write to mailto:a@b.example?subject=Hi."),
        )
    }

    @Test
    fun `an address inside a URL is part of that URL, not a second link`() {
        assertEquals(listOf("https://u@h.example/p" to "https://u@h.example/p"), links("https://u@h.example/p"))
    }

    @Test
    fun `other schemes and lookalikes are left as text`() {
        assertEquals(emptyList<Pair<String, String>>(), links("ftp://a.example javascript:alert(1) xhttps://a.example"))
        assertEquals(emptyList<Pair<String, String>>(), links("https:// nothing"))
    }

    @Test
    fun `links come back in the order they appear`() {
        assertEquals(
            listOf("a@b.example", "https://c.example"),
            links("a@b.example then https://c.example").map { it.first },
        )
    }
}
