package org.ntust.app.tigerduck.wear.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SchedulePersistenceTest {

    /** One course as the phone's `WearScheduleBridge.CourseDto` sends it. */
    private val coursesJson = """
        [{"courseNo":"CS101","courseName":"Algorithms","instructor":"Ada","credits":3,
          "creditsExact":3.0,"classroom":"T3-101","scheduleJson":"{\"1\":[\"2\",\"3\"]}",
          "classroomMapJson":"{}","moodleIdNumber":"1141CS101","customColorHex":null,
          "isManual":false}]
    """.trimIndent()

    private fun write(quietDays: List<String>?) = mutablePreferencesOf().also {
        SchedulePersistence.writeSnapshot(
            it, coursesJson, "#112233", syncedAtMs = 1_000L, loggedIn = true,
            languageTag = "zh-Hant-TW", disableScreenCaptureProtection = false,
            quietDays = quietDays,
        )
    }

    @Test
    fun `the quiet days the phone sends are stored and read back`() {
        val snapshot = SchedulePersistence.readSnapshot(write(listOf("2026-10-09", "2026-10-10")))
        assertEquals(setOf(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 10)), snapshot.quietDays)
        assertEquals("CS101", snapshot.courses.single().courseNo)
    }

    @Test
    fun `a payload from a phone that predates quiet days clears the earlier ones`() {
        val prefs = write(listOf("2026-10-09"))
        SchedulePersistence.writeSnapshot(
            prefs, coursesJson, "#112233", syncedAtMs = 2_000L, loggedIn = true,
            languageTag = null, disableScreenCaptureProtection = false, quietDays = null,
        )
        val snapshot = SchedulePersistence.readSnapshot(prefs)
        assertTrue(snapshot.quietDays.isEmpty())
        assertEquals("CS101", snapshot.courses.single().courseNo)
    }

    /**
     * Upgrade guard: the keys a watch build before quiet days stored, by their
     * on-disk names. The snapshot must load as before, with no quiet days,
     * until the phone's next publish.
     */
    @Test
    fun `a snapshot stored by an older watch build loads with no quiet days`() {
        val stored = preferencesOf(
            stringPreferencesKey("courses_json") to coursesJson,
            stringPreferencesKey("accent_hex") to "#112233",
            longPreferencesKey("synced_at_ms") to 1_000L,
            booleanPreferencesKey("logged_in") to true,
            stringPreferencesKey("language_tag") to "zh-Hant-TW",
            booleanPreferencesKey("disable_screen_capture_protection") to false,
        )
        val snapshot = SchedulePersistence.readSnapshot(stored)
        assertEquals("CS101", snapshot.courses.single().courseNo)
        assertEquals("#112233", snapshot.accentHex)
        assertEquals(1_000L, snapshot.syncedAtMs)
        assertEquals(true, snapshot.loggedIn)
        assertEquals("zh-Hant-TW", snapshot.languageTag)
        assertTrue(snapshot.quietDays.isEmpty())
    }
}
