package org.ntust.app.tigerduck.data.sharing

import org.ntust.app.tigerduck.AppConstants
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * A class table share code read from a QR code: whose class table it is and the course codes of
 * each term it shares. The iOS app reads and writes the same codes; the format is
 * https://github.com/tigerduck-app/tigerduck-app/blob/dev/docs/decisions/0023-class-table-share-code.md
 */
data class ClassTableShareCode(
    val format: Format,
    /** Null only for a TAT code whose sharer left it blank. */
    val studentId: String?,
    /** To the minute; only TigerDuck codes carry it. */
    val sharedAt: Instant?,
    val terms: List<Term>,
) {
    enum class Format {
        TIGER_DUCK,

        /** TAT's code with class times, which the decoder checks and drops. */
        TAT2,

        /** TAT's older code, with no class times. */
        TAT1,
    }

    data class Term(
        /** The app's semester code, such as `1151`, or `114H` for a summer term. */
        val semester: String,
        val courseNos: List<String>,
    )

    companion object {
        const val PREFIX = "HTTPS://TIGERDUCK.APP/T/"

        /**
         * Reads any share code the app accepts, ignoring surrounding whitespace and letter case,
         * and throws a [ClassTableShareCodeError] for anything else.
         */
        fun decode(text: String): ClassTableShareCode {
            val code = text.trim { it in TRIMMED }.uppercase()
            return when {
                code.startsWith(PREFIX) -> decodeTigerDuck(code)
                code.startsWith(TAT_PREFIX) -> decodeTat(code)
                else -> throw ClassTableShareCodeError.UnknownPrefix()
            }
        }

        /**
         * Our code for [terms]: one of the user's terms, or every term that has courses. Course
         * codes outside NTUST's shape are left out, and so is a term left without courses; null
         * when no course is left or the student ID cannot be written.
         */
        fun encode(studentId: String, sharedAt: Instant, terms: List<Term>): String? {
            val id = studentId.uppercase()
            if (!isUpperAlphanumerics(id)) return null
            val semesters = mutableSetOf<String>()
            val kept = terms.mapNotNull { term ->
                val semester = term.semester.uppercase()
                val courseNos = term.courseNos.map { it.uppercase() }.filter(::isCourseNo).toSortedSet().toList()
                if (!isSemester(semester) || courseNos.isEmpty() || !semesters.add(semester)) null
                else Term(semester, courseNos)
            }
            if (kept.isEmpty()) return null
            val text = kept.joinToString(";") { it.courseNos.joinToString(",") }
            val payload = base32(deflated(text.toByteArray(Charsets.US_ASCII)))
            val fields = listOf("1", id, stamp(sharedAt), kept.joinToString("-") { it.semester }, payload)
            val body = PREFIX + fields.joinToString(".")
            return body + "." + checksum(body)
        }

        // --- Our format, version 1 ---

        private fun decodeTigerDuck(code: String): ClassTableShareCode {
            val fields = code.substring(PREFIX.length).split('.')
            if (fields[0] != "1") {
                throw if (isDigits(fields[0])) ClassTableShareCodeError.UnknownVersion()
                else ClassTableShareCodeError.Malformed()
            }
            if (!isAscii(code) || fields.size != 6) throw ClassTableShareCodeError.Malformed()
            if (checksum(code.substring(0, code.lastIndexOf('.'))) != fields[5]) {
                throw ClassTableShareCodeError.ChecksumMismatch()
            }

            val semesters = fields[3].split('-')
            val sharedAt = instant(fields[2])
            if (!isUpperAlphanumerics(fields[1]) || sharedAt == null || !semesters.all(::isSemester) ||
                semesters.toSet().size != semesters.size
            ) {
                throw ClassTableShareCodeError.Malformed()
            }

            val groups = inflatedText(fields[4]).split(';')
            if (groups.size != semesters.size) throw ClassTableShareCodeError.Malformed()
            val terms = semesters.zip(groups) { semester, group -> Term(semester, checkedCourseNos(group.split(','))) }
            return ClassTableShareCode(Format.TIGER_DUCK, fields[1], sharedAt, terms)
        }

        /** `yyyyMMddHHmm` in Taipei time. */
        private fun stamp(instant: Instant): String {
            val calendar = taipeiCalendar().apply { timeInMillis = instant.toEpochMilli() }
            return String.format(
                Locale.ROOT, "%04d%02d%02d%02d%02d",
                calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH),
                calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE),
            )
        }

        private fun instant(text: String): Instant? {
            if (text.length != 12 || !isDigits(text)) return null
            fun number(from: Int, to: Int) = text.substring(from, to).toInt()
            val calendar = taipeiCalendar().apply {
                clear()
                set(number(0, 4), number(4, 6) - 1, number(6, 8), number(8, 10), number(10, 12))
            }
            // The calendar rolls an impossible date such as February 30 over to another day, so a
            // stamp names a date only when that date writes back to the same stamp.
            return Instant.ofEpochMilli(calendar.timeInMillis).takeIf { stamp(it) == text }
        }

        /**
         * Julian before 15 October 1582, like Apple's Gregorian calendar, so the two apps accept
         * the same share times.
         */
        private fun taipeiCalendar() = GregorianCalendar(AppConstants.TAIPEI_TZ, Locale.ROOT)

        // --- Shared rules ---

        private val COURSE_NO = Regex("3?[A-Z]{2}[A-Z0-9]{6,7}")

        /**
         * NTUST's course-number shape, the one `MoodleService` reads from the course-selection
         * list: an optional `3`, two letters, then six or seven letters or digits.
         */
        internal fun isCourseNo(text: String): Boolean = COURSE_NO.matches(text)

        /** The ROC year and the term: `1151`, `1142`, `114H`. */
        internal fun isSemester(text: String): Boolean =
            text.length == 4 && isDigits(text.substring(0, 3)) && isUpperAlphanumerics(text.substring(3))

        /**
         * One term's course codes. An empty or repeated one, which no encoder writes, makes the
         * code malformed; one outside NTUST's shape is an invalid course code.
         */
        internal fun checkedCourseNos(items: List<String>): List<String> {
            if (items.any { it.isEmpty() } || items.toSet().size != items.size) {
                throw ClassTableShareCodeError.Malformed()
            }
            items.firstOrNull { !isCourseNo(it) }?.let { throw ClassTableShareCodeError.InvalidCourseCode(it) }
            return items
        }

        internal fun isAscii(text: String): Boolean = text.all { it < '\u0080' }

        internal fun isDigits(text: String): Boolean = text.isNotEmpty() && text.all { it in '0'..'9' }

        internal fun isUpperAlphanumerics(text: String): Boolean =
            text.isNotEmpty() && text.all { it in '0'..'9' || it in 'A'..'Z' }

        /**
         * What Apple's `CharacterSet.whitespacesAndNewlines` holds, which the iOS decoder trims,
         * so a code reads the same on both platforms.
         */
        private const val TRIMMED =
            "\t\n\u000B\u000C\r \u0085\u00A0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007" +
                "\u2008\u2009\u200A\u200B\u2028\u2029\u202F\u205F\u3000"

        // --- Checksum, compression and Base32 ---

        /** CRC-32 as zlib computes it, written big-endian in Base32. */
        private fun checksum(text: String): String {
            val crc = CRC32().apply { update(text.toByteArray(Charsets.US_ASCII)) }.value
            return base32(ByteArray(4) { (crc shr (24 - 8 * it)).toByte() })
        }

        /**
         * No class table's course codes come near this, and a payload that inflates past it is
         * refused rather than read in full.
         */
        private const val INFLATED_LIMIT = 65_536

        /** Raw DEFLATE (RFC 1951), with no zlib or gzip wrapper. */
        private fun deflated(bytes: ByteArray): ByteArray {
            val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
            try {
                deflater.setInput(bytes)
                deflater.finish()
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (!deflater.finished()) output.write(buffer, 0, deflater.deflate(buffer))
                return output.toByteArray()
            } finally {
                deflater.end()
            }
        }

        /**
         * The payload's text. A stream that stops before its final block ends is refused: Apple's
         * decoder returns whatever it read by then, but no encoder writes such a stream.
         */
        private fun inflatedText(base32Text: String): String {
            val compressed = base32Decoded(base32Text)?.takeIf { it.isNotEmpty() }
                ?: throw ClassTableShareCodeError.Malformed()
            val output = ByteArray(INFLATED_LIMIT)
            var count = 0
            val inflater = Inflater(true)
            try {
                inflater.setInput(compressed)
                while (!inflater.finished() && count < output.size) {
                    val read = inflater.inflate(output, count, output.size - count)
                    if (read == 0) break
                    count += read
                }
                // A full buffer may hold a cut-off text.
                if (!inflater.finished() || count == 0 || count == output.size) {
                    throw ClassTableShareCodeError.Malformed()
                }
            } catch (_: DataFormatException) {
                throw ClassTableShareCodeError.Malformed()
            } finally {
                inflater.end()
            }
            if ((0 until count).any { output[it] < 0 }) throw ClassTableShareCodeError.Malformed()
            return String(output, 0, count, Charsets.US_ASCII)
        }

        private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

        /** RFC 4648 Base32 without `=` padding. */
        internal fun base32(bytes: ByteArray): String {
            val output = StringBuilder()
            var buffer = 0
            var bits = 0
            for (byte in bytes) {
                buffer = ((buffer shl 8) or (byte.toInt() and 0xFF)) and 0xFFF
                bits += 8
                while (bits >= 5) {
                    bits -= 5
                    output.append(BASE32_ALPHABET[(buffer shr bits) and 31])
                }
            }
            if (bits > 0) output.append(BASE32_ALPHABET[(buffer shl (5 - bits)) and 31])
            return output.toString()
        }

        /**
         * Null for a character outside the alphabet, or for a length or trailing bits no encoder
         * writes (RFC 4648, section 3.5).
         */
        internal fun base32Decoded(text: String): ByteArray? {
            val output = ByteArrayOutputStream()
            var buffer = 0
            var bits = 0
            for (character in text) {
                val value = BASE32_ALPHABET.indexOf(character)
                if (value < 0) return null
                buffer = ((buffer shl 5) or value) and 0xFFF
                bits += 5
                if (bits >= 8) {
                    bits -= 8
                    output.write(buffer shr bits)
                }
            }
            if (bits >= 5 || buffer and ((1 shl bits) - 1) != 0) return null
            return output.toByteArray()
        }
    }
}

/**
 * Why a share code was refused. The import screen shows one message for all but
 * [UnknownPrefix], which a scanner skips, but they stay apart for tests and logs.
 */
sealed class ClassTableShareCodeError : Exception() {
    /** Not a share code, such as another app's QR code. */
    class UnknownPrefix : ClassTableShareCodeError()

    /** A share code in a format version this app does not know yet. */
    class UnknownVersion : ClassTableShareCodeError()

    class Malformed : ClassTableShareCodeError()

    class ChecksumMismatch : ClassTableShareCodeError()

    data class InvalidCourseCode(val courseNo: String) : ClassTableShareCodeError()
}
