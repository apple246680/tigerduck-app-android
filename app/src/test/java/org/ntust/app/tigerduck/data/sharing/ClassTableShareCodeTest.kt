package org.ntust.app.tigerduck.data.sharing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.data.sharing.ClassTableShareCode.Format
import org.ntust.app.tigerduck.data.sharing.ClassTableShareCode.Term
import org.ntust.app.tigerduck.shared.Course
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * The share code formats. The vectors are copied verbatim from tigerduck-app's
 * docs/decisions/0023-class-table-share-code.md, written there by an encoder other than either
 * app's, so these tests read other platforms' codes. Our encoder's output is only read back here:
 * DEFLATE encoders differ, so it is never compared with another platform's.
 */
class ClassTableShareCodeTest {

    private companion object {
        /** 2026-10-13 14:05 and 2026-02-20 09:30, Taipei time. */
        val OCTOBER: Instant = Instant.ofEpochSecond(1_791_871_500)
        val FEBRUARY: Instant = Instant.ofEpochSecond(1_771_551_000)

        // The vectors of docs/decisions/0023-class-table-share-code.md, in its order.
        const val ONE_TERM = "HTTPS://TIGERDUCK.APP/T/1.B11300001.202610131405.1151." +
            "OMHDMNBQGQYDAMGUOGDLFDDULROWCYV6RYYFMACMBQAA.E2YUDIQ"
        const val STORED_BLOCK = "HTTPS://TIGERDUCK.APP/T/1.B11300001.202610131405.1151." +
            "AEYQBTX7INJTCMBRGAYDAMJMINJTCMBRGAYDAMRMIVCTCMBRGAYDAMJMJVATCMBRGAYDAMJMKBCTCMBRGAYDAMI.Y4XVOOA"
        const val SEVERAL_TERMS = "HTTPS://TIGERDUCK.APP/T/1.M11300002.202602200930.1151-114H-1142." +
            "GN3A4NRUGA2DAMBQ2KA3CDDV3RO2CLDLL5D2RLBVJTLFRR6VCWTMGHOOBKALCAA.XSAWPTA"

        /** The first code with its payload's first `O` changed to `P`. */
        const val CHANGED = "HTTPS://TIGERDUCK.APP/T/1.B11300001.202610131405.1151." +
            "PMHDMNBQGQYDAMGUOGDLFDDULROWCYV6RYYFMACMBQAA.E2YUDIQ"
        const val BAD_COURSE = "HTTPS://TIGERDUCK.APP/T/1.B11300001.202610131405.1151.OMHDMNBQGQYDAMGUOGDLAAA.5GDOVTY"
        const val NEWER = "HTTPS://TIGERDUCK.APP/T/2.B11300001.202610131405.1151.OMHDMNBQGQYDAMAEAA.O6Y5T6Y"
        const val TAT2 = "HTTPS://NTUST-TAT.WEB.APP/S/TAT21151B11300001CS1010001.267-EE1010002.13.5N-MA1010003"
        const val TAT1 = "HTTPS://NTUST-TAT.WEB.APP/S/TAT11142B11300002CS1010004-PE1010005"

        val ONE_TERM_CODE = ClassTableShareCode(
            format = Format.TIGER_DUCK,
            studentId = "B11300001",
            sharedAt = OCTOBER,
            terms = listOf(Term("1151", listOf("CS1010001", "CS1010002", "EE1010001", "MA1010001", "PE1010001"))),
        )
        val SEVERAL_TERMS_CODE = ClassTableShareCode(
            format = Format.TIGER_DUCK,
            studentId = "M11300002",
            sharedAt = FEBRUARY,
            terms = listOf(
                Term("1151", listOf("3CS1010002", "CS1010001", "GE1010001")),
                Term("114H", listOf("MA1010002")),
                Term("1142", listOf("CS1010003", "EE1010002", "GE1010002", "PE1010002")),
            ),
        )
    }

    private inline fun <reified T : ClassTableShareCodeError> assertRefused(code: String): T =
        assertThrows(code, T::class.java) { ClassTableShareCode.decode(code) }

    /** Our code around [plain], deflated here rather than by the encoder, with a checksum that matches. */
    private fun codeAround(plain: ByteArray, cut: Int = 0): String {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(plain)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        val compressed = out.toByteArray().let { it.copyOf(it.size - cut) }
        val body = ClassTableShareCode.PREFIX +
            listOf("1", "B11300001", "202610131405", "1151", ClassTableShareCode.base32(compressed)).joinToString(".")
        val crc = CRC32().apply { update(body.toByteArray(Charsets.US_ASCII)) }.value
        return body + "." + ClassTableShareCode.base32(ByteArray(4) { (crc shr (24 - 8 * it)).toByte() })
    }

    /** [length] characters of distinct course codes, nine or ten characters each, joined by `,`. */
    private fun distinctCourseNos(length: Int): String {
        val count = (length + 1) / 10
        val longer = length - (10 * count - 1)
        return (0 until count).joinToString(",") { (if (it < longer) "3AA" else "AA") + it.toString().padStart(7, '0') }
            .also { assertEquals(length, it.length) }
    }

    // --- The decision record's vectors ---

    @Test
    fun `the decision record's codes decode`() {
        assertEquals(ONE_TERM_CODE, ClassTableShareCode.decode(ONE_TERM))
        assertEquals(ONE_TERM_CODE, ClassTableShareCode.decode(STORED_BLOCK))
        assertEquals(SEVERAL_TERMS_CODE, ClassTableShareCode.decode(SEVERAL_TERMS))
        assertEquals(ONE_TERM_CODE, ClassTableShareCode.decode(" ${ONE_TERM.lowercase()}\n"))
    }

    @Test
    fun `the decision record's refused codes are refused for its reasons`() {
        assertRefused<ClassTableShareCodeError.ChecksumMismatch>(CHANGED)
        assertEquals("CS10100", assertRefused<ClassTableShareCodeError.InvalidCourseCode>(BAD_COURSE).courseNo)
        assertRefused<ClassTableShareCodeError.UnknownVersion>(NEWER)
    }

    @Test
    fun `the decision record's TAT codes decode, version 2 dropping its class times`() {
        assertEquals(
            ClassTableShareCode(
                Format.TAT2, "B11300001", null, listOf(Term("1151", listOf("CS1010001", "EE1010002", "MA1010003"))),
            ),
            ClassTableShareCode.decode(TAT2),
        )
        assertEquals(
            ClassTableShareCode(Format.TAT1, "B11300002", null, listOf(Term("1142", listOf("CS1010004", "PE1010005")))),
            ClassTableShareCode.decode(TAT1),
        )
    }

    // --- Our format ---

    @Test
    fun `one term comes back as it went in, in QR alphanumeric characters only`() {
        val code = ClassTableShareCode.encode(
            studentId = "b11300001",
            sharedAt = OCTOBER.plusSeconds(42),
            terms = listOf(Term("1151", listOf("PE1010001", "CS1010002", "MA1010001", "CS1010001", "EE1010001", "CS1010002"))),
        )!!

        assertTrue(code, code.all { it in "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:" })
        assertTrue(code, code.startsWith("HTTPS://TIGERDUCK.APP/T/1.B11300001.202610131405.1151."))
        assertEquals(ONE_TERM_CODE, ClassTableShareCode.decode(code))
    }

    @Test
    fun `several terms keep their order, and a term left without valid courses is not written`() {
        val code = ClassTableShareCode.encode(
            studentId = "M11300002",
            sharedAt = FEBRUARY,
            terms = listOf(
                Term("1151", listOf("GE1010001", "3CS1010002", "CS1010001", "NOT-A-CODE")),
                Term("1152", listOf("CS101")),
                Term("114h", listOf("MA1010002")),
                Term("1142", listOf("PE1010002", "EE1010002", "GE1010002", "CS1010003")),
                Term("1151", listOf("PE1010009")),
            ),
        )!!

        assertEquals(SEVERAL_TERMS_CODE, ClassTableShareCode.decode(code))
    }

    @Test
    fun `nothing to share writes no code`() {
        assertNull(ClassTableShareCode.encode("B11300001", OCTOBER, emptyList()))
        assertNull(ClassTableShareCode.encode("B11300001", OCTOBER, listOf(Term("1151", listOf("CS101")))))
        assertNull(ClassTableShareCode.encode("B11300001", OCTOBER, listOf(Term("11511", listOf("CS1010001")))))
        assertNull(ClassTableShareCode.encode("B113.0001", OCTOBER, listOf(Term("1151", listOf("CS1010001")))))
        assertNull(ClassTableShareCode.encode("", OCTOBER, listOf(Term("1151", listOf("CS1010001")))))
    }

    @Test
    fun `changing any one character after the version fails the checksum`() {
        for (vector in listOf(ONE_TERM, SEVERAL_TERMS)) {
            for (index in ClassTableShareCode.PREFIX.length + 2 until vector.length) {
                val original = vector[index]
                if (!original.isLetterOrDigit()) continue
                val replacement = when {
                    original.isLetter() -> if (original == 'A') 'B' else 'A'
                    else -> if (original == '2') '3' else '2'
                }
                val changed = vector.substring(0, index) + replacement + vector.substring(index + 1)
                assertRefused<ClassTableShareCodeError.ChecksumMismatch>(changed)
            }
        }
    }

    @Test
    fun `a course code outside NTUST's shape is refused, though the checksum matches`() {
        assertEquals("CS10100", assertRefused<ClassTableShareCodeError.InvalidCourseCode>(BAD_COURSE).courseNo)
        assertEquals(
            "123456789",
            assertRefused<ClassTableShareCodeError.InvalidCourseCode>(
                "HTTPS://NTUST-TAT.WEB.APP/S/TAT21151B11300001123456789.12",
            ).courseNo,
        )
        // Course codes come out of the payload as they went in: a lower-case one is not NTUST's.
        val lowerCase = codeAround("cs1010001".toByteArray())
        assertEquals("cs1010001", assertRefused<ClassTableShareCodeError.InvalidCourseCode>(lowerCase).courseNo)
    }

    @Test
    fun `a code with a sound checksum but a broken layout is malformed`() {
        for (code in listOf(
            "HTTPS://TIGERDUCK.APP/T/1.B11300001.202610131405.1151-1151.OMHDMNBQGQYDAMFUO2DLFDAA.4HFLDLQ",
            "HTTPS://TIGERDUCK.APP/T/1.B11300001.202610131405.1151-1142.OMHDMNBQGQYDAMAEAA.XT6KUZI",
            "HTTPS://TIGERDUCK.APP/T/1.B11300001.202610131405.1151-1142.OMHDMNBQGQYDAMFUAYAA.5A4SDJY",
            "HTTPS://TIGERDUCK.APP/T/1.B11300001.202602300930.1151.OMHDMNBQGQYDAMAEAA.MASPS5I",
            "HTTPS://TIGERDUCK.APP/T/1.B11300001.202610131405.1151.OMHDMNBQGQYDAMGUOGDLCAA.5IBD5II",
            "HTTPS://TIGERDUCK.APP/T/1.B11300001.202610131405.1151",
            "HTTPS://TIGERDUCK.APP/T/V1.B11300001",
            codeAround("CS1010001,CS1010001".toByteArray()),
            codeAround("CS1010001\u0080".toByteArray(Charsets.ISO_8859_1)),
        )) {
            assertRefused<ClassTableShareCodeError.Malformed>(code)
        }
    }

    @Test
    fun `a payload is read up to 64 KiB and refused from there`() {
        val longest = ClassTableShareCode.decode(codeAround(distinctCourseNos(65_535).toByteArray()))
        assertEquals(6_553, longest.terms.single().courseNos.size)

        assertRefused<ClassTableShareCodeError.Malformed>(codeAround(distinctCourseNos(65_536).toByteArray()))
    }

    @Test
    fun `a payload whose DEFLATE stream stops early is malformed`() {
        val plain = "CS1010001,CS1010002,EE1010001,MA1010001,PE1010001".toByteArray()
        assertEquals(ONE_TERM_CODE, ClassTableShareCode.decode(codeAround(plain)))

        assertRefused<ClassTableShareCodeError.Malformed>(codeAround(plain, cut = 3))
    }

    @Test
    fun `other QR codes and newer versions are told apart from damaged codes`() {
        val others = listOf("https://example.com/T/1.B11300001", "WIFI:S:home;T:WPA;P:secret;;", "", "HTTPS://TIGERDUCK.APP/X/1.")
        for (other in others) {
            assertRefused<ClassTableShareCodeError.UnknownPrefix>(other)
        }
        for (newer in listOf(NEWER, "HTTPS://TIGERDUCK.APP/T/12", "HTTPS://NTUST-TAT.WEB.APP/S/TAT31151B11300001CS1010001")) {
            assertRefused<ClassTableShareCodeError.UnknownVersion>(newer)
        }
    }

    @Test
    fun `whitespace is trimmed as the iOS app trims it`() {
        assertEquals(ONE_TERM_CODE, ClassTableShareCode.decode("\u00A0\t$ONE_TERM\u0085\u3000"))
        assertRefused<ClassTableShareCodeError.UnknownPrefix>("\uFEFF$ONE_TERM")
    }

    // --- TAT ---

    @Test
    fun `a TAT version 2 code may pad or leave out its student ID`() {
        val padded = ClassTableShareCode.decode("HTTPS://NTUST-TAT.WEB.APP/S/TAT2114HB113     GE1010001.6AB")
        assertEquals("B113", padded.studentId)
        assertEquals(listOf(Term("114H", listOf("GE1010001"))), padded.terms)

        val blank = ClassTableShareCode.decode("HTTPS://NTUST-TAT.WEB.APP/S/TAT21151         ")
        assertNull(blank.studentId)
        assertEquals(listOf(Term("1151", emptyList())), blank.terms)
    }

    @Test
    fun `a TAT code with a weekday, period or course code length TAT never writes is malformed`() {
        for (code in listOf(
            "HTTPS://NTUST-TAT.WEB.APP/S/TAT21151B11300001CS1010001.83",
            "HTTPS://NTUST-TAT.WEB.APP/S/TAT21151B11300001CS1010001.3Z",
            "HTTPS://NTUST-TAT.WEB.APP/S/TAT21151B11300001CS1010001.3",
            "HTTPS://NTUST-TAT.WEB.APP/S/TAT21151B113000013CS1010001",
            "HTTPS://NTUST-TAT.WEB.APP/S/TAT21151B11300001CS1010001-",
            "HTTPS://NTUST-TAT.WEB.APP/S/TAT21151B11300001CS1010001-CS1010001",
            "HTTPS://NTUST-TAT.WEB.APP/S/TAT2115",
            "HTTPS://NTUST-TAT.WEB.APP/S/XYZ21151B11300001CS1010001",
        )) {
            assertRefused<ClassTableShareCodeError.Malformed>(code)
        }
    }

    @Test
    fun `the TAT encoder writes each course's weekdays and periods in TAT's letters, and the code decodes`() {
        val courses = listOf(
            Course.fromSchedule("CS1010001", "A", schedule = mapOf(2 to listOf("8", "7"))),
            Course.fromSchedule("EE1010002", "B", schedule = mapOf(5 to listOf("5"), 1 to listOf("3"))),
            Course.fromSchedule("GE1010003", "C", schedule = mapOf(3 to listOf("10", "A", "9"), 6 to listOf("D"))),
            Course.fromSchedule("MA1010004", "D"),
            Course.fromSchedule("3CS1010005", "E", schedule = mapOf(1 to listOf("1"))),
            Course.fromSchedule("CS1010001", "A again", schedule = mapOf(4 to listOf("1"))),
            Course.fromSchedule("PE1010006", "F", schedule = mapOf(8 to listOf("1"), 4 to listOf("X"))),
        )

        val code = ClassTableShareCode.encodeTat("B11300001", "1151", courses)!!

        // A TAT code holds no compressed bytes, so its text is fixed by the decision record.
        assertEquals(
            "HTTPS://NTUST-TAT.WEB.APP/S/TAT21151B11300001" +
                "CS1010001.267-EE1010002.13.5N-GE1010003.389A.6D-MA1010004-PE1010006",
            code,
        )
        assertEquals(
            ClassTableShareCode(
                Format.TAT2, "B11300001", null,
                listOf(Term("1151", listOf("CS1010001", "EE1010002", "GE1010003", "MA1010004", "PE1010006"))),
            ),
            ClassTableShareCode.decode(code),
        )
        assertEquals("B113", ClassTableShareCode.decode(ClassTableShareCode.encodeTat("b113", "1151", courses)!!).studentId)
        assertNull(ClassTableShareCode.encodeTat("B113000011", "1151", courses))
        assertNull(ClassTableShareCode.encodeTat("B11300001", "1151", listOf(courses[4])))
    }
}
