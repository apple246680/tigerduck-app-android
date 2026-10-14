package org.ntust.app.tigerduck.data.cache

import android.app.Application
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.academic.AcademicCalendarStore
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseGrades
import org.ntust.app.tigerduck.network.model.moodle.MoodleGradeItem
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** The per-account Moodle copies the course screens draw from. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DataCacheMoodleTest {

    private lateinit var context: Application
    private lateinit var cache: DataCache
    private val type = object : TypeToken<MoodleCourseGrades>() {}.type

    private val grades = MoodleCourseGrades(
        listOf(MoodleGradeItem(1, "HW1", "mod", "assign", 31, "95", 100.0, false, "Nice")),
    )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        cache = DataCache(context, AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context)))
    }

    @Test
    fun `a copy comes back with the time it was fetched, for its own account only`() = runTest {
        cache.saveMoodleData(grades, 1234L, "courses/5/grades", "B11300001")

        val loaded = cache.loadMoodleData<MoodleCourseGrades>(type, "courses/5/grades", "B11300001")
        assertEquals(grades, loaded!!.value)
        assertEquals(1234L, loaded.fetchedAtMs)
        assertNull(cache.loadMoodleData<MoodleCourseGrades>(type, "courses/5/grades", "B11300002"))
    }

    @Test
    fun `a copy in another shape reads as absent`() = runTest {
        cache.saveMoodleData(grades, 1L, "courses/5/grades", "B11300001")
        val file = File(cache.moodleFilesDir("B11300001"), "courses/5/grades.json")
        file.writeText(file.readText().replace("\"v\":${DataCache.MOODLE_CACHE_SCHEMA}", "\"v\":0"))

        assertNull(cache.loadMoodleData<MoodleCourseGrades>(type, "courses/5/grades", "B11300001"))
    }

    @Test
    fun `sign-out wipes every account's copies, pictures and opened files`() = runTest {
        cache.saveMoodleData(grades, 1L, "courses/5/grades", "B11300001")
        val picture = File(cache.moodleFilesDir("B11300001"), "pictures/1-3").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1))
        }

        val opened = File(cache.moodleOpenedFilesDir("B11300001"), "90/hw3.pdf").apply {
            parentFile!!.mkdirs()
            writeText("pdf")
        }

        cache.clearAllUserData()

        assertNull(cache.loadMoodleData<MoodleCourseGrades>(type, "courses/5/grades", "B11300001"))
        assertFalse(picture.exists())
        assertFalse("opened attachments go too", opened.exists())
    }

    @Test
    fun `a name cannot climb out of the account's folder`() = runTest {
        cache.saveMoodleData(grades, 1L, "../../escape", "B11300001")

        assertFalse(File(context.cacheDir, "escape.json").exists())
        assertEquals(grades, cache.loadMoodleData<MoodleCourseGrades>(type, "escape", "B11300001")!!.value)
    }

    @Test
    fun `the opened files' folder does not name the account, nor hash it where anyone could redo it`() {
        val dir = cache.moodleOpenedFilesDir("B11300001")
        assertFalse(dir.path.contains("B11300001", ignoreCase = true))
        val plainHash = java.security.MessageDigest.getInstance("SHA-256").digest("B11300001".toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
        assertFalse("salted per install", dir.name == plainHash)
        assertEquals(dir, cache.moodleOpenedFilesDir("B11300001"))
        assertEquals(
            "the same on this install after a restart",
            dir,
            DataCache(context, AcademicCalendarStore(context, OkHttpClient(), AppPreferences(context))).moodleOpenedFilesDir("B11300001"),
        )
        assertFalse(dir == cache.moodleOpenedFilesDir("B11300002"))
    }
}
