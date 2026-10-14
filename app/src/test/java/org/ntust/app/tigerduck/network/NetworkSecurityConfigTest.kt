package org.ntust.app.tigerduck.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.BuildConfig
import org.w3c.dom.Element
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import javax.xml.parsers.DocumentBuilderFactory

/**
 * After a pin-set's expiration date the platform stops checking its pins and
 * falls back to system CA trust without a word, so these fail while there is
 * still time to rotate the pins in both apps (the Apple repo's
 * tls-pin-rotation skill covers both) and ship them. They also keep the debug
 * config and PIN_EXPIRY_EPOCH, which a rotation has to touch too, in step.
 */
class NetworkSecurityConfigTest {

    private data class Domain(val name: String, val includeSubdomains: Boolean)

    private data class PinSet(val domains: List<Domain>, val expiration: LocalDate, val pins: Set<String>)

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }
            .filterIsInstance<Element>()
            .filter { it.tagName == tag }

    private fun pinSets(path: String): List<PinSet> {
        val pinSets = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
            .getElementsByTagName("pin-set")
        return (0 until pinSets.length).map { pinSets.item(it) as Element }.map { pinSet ->
            PinSet(
                domains = (pinSet.parentNode as Element).children("domain").map {
                    // An absent includeSubdomains means false, as the platform reads it.
                    Domain(it.textContent.trim(), it.getAttribute("includeSubdomains").toBoolean())
                },
                expiration = LocalDate.parse(pinSet.getAttribute("expiration")),
                pins = pinSet.children("pin").map { it.textContent.trim() }.toSet(),
            )
        }
    }

    private val release = pinSets("src/main/res/xml/network_security_config.xml")

    @Test
    fun `no pin-set is within 45 days of its expiration`() {
        val deadline = LocalDate.now(ZoneOffset.UTC).plusDays(45)
        assertTrue(release.isNotEmpty())
        release.forEach { set ->
            assertTrue(
                "The ${set.domains.map { it.name }} pins expire on ${set.expiration}; rotate them in both apps",
                set.expiration >= deadline,
            )
        }
    }

    @Test
    fun `the debug config pins the same hosts with the same pins and dates`() {
        assertEquals(release, pinSets("src/debug/res/xml/network_security_config.xml"))
    }

    // The watch calls the library with the same account the phone pins it for, so a
    // rotation that skips the watch's copy fails here.
    @Test
    fun `the watch pins the library with the phone's pins and date`() {
        val library = release.filter { set -> set.domains.any { it.name == "api.lib.ntust.edu.tw" } }
        assertEquals(1, library.size)
        assertEquals(library, pinSets("../wear/src/main/res/xml/network_security_config.xml"))
    }

    @Test
    fun `the watch applies its config and allows no cleartext`() {
        val parser = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val application = parser.parse(File("../wear/src/main/AndroidManifest.xml"))
            .getElementsByTagName("application").item(0) as Element
        assertEquals("@xml/network_security_config", application.getAttribute("android:networkSecurityConfig"))
        val config = parser.parse(File("../wear/src/main/res/xml/network_security_config.xml"))
        val base = config.getElementsByTagName("base-config")
        assertEquals("false", (base.item(0) as Element).getAttribute("cleartextTrafficPermitted"))
        val domains = config.getElementsByTagName("domain-config")
        (0 until domains.length).map { domains.item(it) as Element }.forEach {
            // Absent, a domain-config inherits the base's false.
            assertTrue(it.getAttribute("cleartextTrafficPermitted") != "true")
        }
    }

    @Test
    fun `PIN_EXPIRY_EPOCH is the earliest pin-set expiration`() {
        val earliest = release.minOf { it.expiration }
        assertEquals(
            earliest.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            BuildConfig.PIN_EXPIRY_EPOCH,
        )
    }
}
