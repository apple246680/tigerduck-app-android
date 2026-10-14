package org.ntust.app.tigerduck.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.BuildConfig
import org.w3c.dom.Element
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
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

    private fun certificates(resource: String): List<X509Certificate> =
        requireNotNull(javaClass.getResourceAsStream(resource)) { "missing $resource" }.use { stream ->
            CertificateFactory.getInstance("X.509").generateCertificates(stream).map { it as X509Certificate }
        }

    /** The pin value: base64 of the SHA-256 of the certificate's SubjectPublicKeyInfo. */
    private val X509Certificate.spkiSha256: String
        get() = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(publicKey.encoded))

    private val libraryPins: Set<String>
        get() = release.single { set -> set.domains.any { it.name == "api.lib.ntust.edu.tw" } }.pins

    /**
     * Sectigo Public Server Authentication Root E46 and Root R46, as crt.sectigo.com, Mozilla
     * and Apple publish them. Sectigo issues new public TLS certificates only under these two:
     * ECC ones under E46, RSA ones under R46.
     */
    private val sectigoRoots get() = certificates("/tls/sectigo-public-server-authentication-roots.pem")

    @Test
    fun `the Sectigo root fixture holds the roots Sectigo and the system stores publish`() {
        // SHA-256 fingerprints of Root E46 and Root R46 as crt.sectigo.com, Mozilla and the
        // macOS system root store list them, so a swapped fixture can't vouch for its own pins.
        val fingerprints = sectigoRoots.map { root ->
            MessageDigest.getInstance("SHA-256").digest(root.encoded).joinToString("") { "%02X".format(it) }
        }.toSet()
        assertEquals(
            setOf(
                "C90F26F0FB1B4018B22227519B5CA2B53E2CA5B3BE5CF18EFE1BEF47380C5383",
                "7BB647A62AEEAC88BF257AA522D01FFEA395E0AB45C73F93F65654EC38F25A06",
            ),
            fingerprints,
        )
    }

    @Test
    fun `every library pin is the key of a certificate its host serves or a Sectigo root`() {
        // The chain api.lib.ntust.edu.tw served on 2026-10-14 (public data): its leaf, Sectigo
        // DV E36, and Root E46 cross-signed by USERTrust ECC.
        val known = (certificates("/tls/api.lib.ntust.edu.tw.pem") + sectigoRoots).map { it.spkiSha256 }.toSet()
        assertEquals(emptySet<String>(), libraryPins - known)
    }

    @Test
    fun `the library pins survive a renewal with a new key and issuer from either Sectigo root`() {
        // Every renewal since 2022 changed the library's key and issuer, once from ECC to RSA.
        // A pinned root keeps any certificate issued under it valid until the next rotation.
        sectigoRoots.forEach { root ->
            assertTrue("${root.subjectX500Principal.name} is not pinned", root.spkiSha256 in libraryPins)
        }
    }
}
