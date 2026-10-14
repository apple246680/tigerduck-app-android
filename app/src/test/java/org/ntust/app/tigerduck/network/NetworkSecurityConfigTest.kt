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

    /**
     * The configs are read through paths Gradle hands the test together with declaring the
     * files as inputs of the test task. A path typed here instead would be no input, so an
     * edit to the XML alone would leave the task UP-TO-DATE and these checks would not run.
     */
    private fun declaredInput(property: String): String =
        checkNotNull(System.getProperty(property)) {
            "$property is not set: declare the file as an input of the unit-test tasks in app/build.gradle.kts"
        }

    private val release = pinSets(declaredInput("tigerduck.nsc.release"))

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
        assertEquals(release, pinSets(declaredInput("tigerduck.nsc.debug")))
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
