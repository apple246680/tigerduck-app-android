package org.ntust.app.tigerduck.data.sharing

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.data.sharing.ClassTableShareCode.Format
import org.ntust.app.tigerduck.data.sharing.ClassTableShareCode.Term
import org.ntust.app.tigerduck.shared.Course
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * [ImportedClassTableStore] over its real file, in Robolectric's per-test app folders: the order
 * and replacement rules, the shown person, the file it keeps, and when it empties.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the real one is a Hilt app these tests do not need.
@Config(application = Application::class)
class ImportedClassTableStoreTest {

    private val context: Application = RuntimeEnvironment.getApplication()
    private val file get() = ImportedClassTableStore.fileIn(context)

    /** The student ID the store reads as signed in; a test changes it to sign another account in. */
    private var account: String? = "B11300000"

    private fun store() = ImportedClassTableStore({ file }, { account }, Dispatchers.Unconfined)

    private fun code(studentId: String?, vararg terms: Term, sharedAt: Instant? = null) =
        ClassTableShareCode(if (studentId == null) Format.TAT2 else Format.TIGER_DUCK, studentId, sharedAt, terms.toList())

    private val fall = Term("1151", listOf("CS1010001", "MA1010001"))
    private val spring = Term("1142", listOf("EE1010002"))

    private fun ImportedClassTableStore.nicknames() = tables.value.people.map { it.nickname }

    @Test
    fun `the file is in the no-backup folder`() {
        assertEquals(context.noBackupFilesDir, file.parentFile)
    }

    @Test
    fun `people stay in the order they were imported`() = runTest {
        val store = store()

        store.add(code("B11300001", fall), "Ming", emptyMap())
        store.add(code("B11300002", fall), "B11300002", emptyMap())
        store.add(code(null, spring), "Hua", emptyMap())

        assertEquals(listOf("Ming", "B11300002", "Hua"), store.nicknames())
        assertTrue(store.tables.value.hasImportedClassTables)
    }

    @Test
    fun `importing a student ID again replaces its terms and keeps its nickname, entry and place`() = runTest {
        val store = store()
        val first = store.add(code("B11300001", fall), "Ming", emptyMap())!!
        store.add(code("B11300002", fall), "Hua", emptyMap())
        val sharedAt = Instant.ofEpochSecond(1_791_871_500)
        val importedAt = Instant.ofEpochSecond(1_791_900_000)

        val again = store.add(
            code("B11300001", spring, sharedAt = sharedAt),
            "Someone else",
            mapOf("1142" to listOf(Course(courseNo = "EE1010002", courseName = "Circuits"))),
            importedAt,
        )

        assertEquals(listOf("Ming", "Hua"), store.nicknames())
        assertEquals(first.id, again?.id)
        val replaced = store.tables.value.person("B11300001")!!
        assertEquals(first.id, replaced.id)
        assertEquals(sharedAt, replaced.sharedAt)
        assertEquals(importedAt, replaced.importedAt)
        assertEquals(listOf("1142"), replaced.terms.map { it.semester })
        assertEquals(listOf("EE1010002"), replaced.terms.single().courseNos)
        assertEquals(listOf("Circuits"), replaced.terms.single().courses.map { it.courseName })
    }

    @Test
    fun `a new person needs a nickname, an update keeps its own, and a code without a student ID is always new`() = runTest {
        val store = store()

        assertNull(store.add(code("B11300001", fall), "  \n", emptyMap()))
        store.add(code("B11300001", fall), "Ming", emptyMap())
        assertNotNull(store.add(code("B11300001", spring), "", emptyMap()))
        store.add(code(null, fall), "Hua", emptyMap())
        store.add(code(null, fall), " Hua ", emptyMap())

        assertEquals(listOf("Ming", "Hua", "Hua"), store.nicknames())
    }

    @Test
    fun `rename changes the nickname but not to a blank one, and delete removes the person`() = runTest {
        val store = store()
        val ming = store.add(code("B11300001", fall), "Ming", emptyMap())!!
        val hua = store.add(code("B11300002", fall), "Hua", emptyMap())!!

        store.rename(ming.id, " Xiao Ming ")
        store.rename(hua.id, " ")
        assertEquals(listOf("Xiao Ming", "Hua"), store.nicknames())

        store.delete(ming.id)
        assertEquals(listOf(hua.id), store.tables.value.people.map { it.id })
        assertEquals(listOf("Hua"), store().load().people.map { it.nickname })
    }

    @Test
    fun `deleting the shown person shows the user's own class table again, as a relaunch does`() = runTest {
        val store = store()
        val ming = store.add(code("B11300001", fall), "Ming", emptyMap())!!
        val hua = store.add(code("B11300002", fall), "Hua", emptyMap())!!

        store.show(hua.id)
        store.show("someone never imported")
        assertEquals(hua.id, store.tables.value.shownPerson?.id)
        assertNull(store().load().shownPersonId)

        store.delete(ming.id)
        assertEquals(hua.id, store.tables.value.shownPersonId)
        store.delete(hua.id)
        assertNull(store.tables.value.shownPersonId)
        assertNull(store.tables.value.shownPerson)
    }

    @Test
    fun `a new store reads back everything the last one kept`() = runTest {
        val store = store()
        val importedAt = Instant.ofEpochMilli(1_791_900_000_500)
        val sharedAt = Instant.ofEpochSecond(1_791_871_500)
        val course = Course.fromSchedule(
            courseNo = "CS1010001",
            // Data structures, in Chinese, as most course names and nicknames are.
            courseName = "\u8CC7\u6599\u7D50\u69CB",
            instructor = "Teacher",
            credits = 3f,
            classroom = "TR-313",
            schedule = mapOf(2 to listOf("3", "4")),
            classroomMap = mapOf("2-3" to "TR-313", "2-4" to "TR-313"),
        )
        store.add(
            code("B11300001", fall, spring, sharedAt = sharedAt), "\u5C0F\u660E", mapOf("1151" to listOf(course)), importedAt,
        )
        store.add(code(null, spring), "Hua", emptyMap(), importedAt)

        val reloaded = store().load().people

        assertEquals(store.tables.value.people, reloaded)
        assertEquals(listOf("\u5C0F\u660E", "Hua"), reloaded.map { it.nickname })
        assertEquals(listOf("B11300001", null), reloaded.map { it.studentId })
        assertEquals(listOf(importedAt, importedAt), reloaded.map { it.importedAt })
        assertEquals(listOf(sharedAt, null), reloaded.map { it.sharedAt })
        val terms = reloaded.first().terms
        assertEquals(listOf("1151", "1142"), terms.map { it.semester })
        assertEquals(listOf(listOf("CS1010001", "MA1010001"), listOf("EE1010002")), terms.map { it.courseNos })
        val detail = terms.first().courses.single()
        assertEquals("\u8CC7\u6599\u7D50\u69CB", detail.courseName)
        assertEquals(mapOf(2 to listOf("3", "4")), detail.schedule)
        assertEquals("TR-313", detail.classroom(2, "4"))
        assertTrue(terms.last().courses.isEmpty())
    }

    @Test
    fun `lists the caller changes after an import do not change the person imported`() = runTest {
        val store = store()
        val courseNos = mutableListOf("CS1010001")
        val courses = mutableListOf(Course(courseNo = "CS1010001", courseName = "Circuits"))

        store.add(code("B11300001", Term("1151", courseNos)), "Ming", mapOf("1151" to courses))
        courseNos.clear()
        courses.clear()

        val term = store.tables.value.people.single().terms.single()
        assertEquals(listOf("CS1010001"), term.courseNos)
        assertEquals(listOf("Circuits"), term.courses.map { it.courseName })
        assertEquals(store.tables.value.people, store().load().people)
    }

    @Test
    fun `a file that does not decode, as one from a later layout might not, is not written over`() = runTest {
        for (kept in listOf(
            """{"version":1,"people":"written by a later version"}""",
            """{"version":2,"owner":null,"people":[]}""",
            """{"version":1,"owner":null,"people":[{"id":"x","importedAtMillis":0,"terms":[]}]}""",
            "not JSON",
        )) {
            file.parentFile!!.mkdirs()
            file.writeText(kept)
            val store = store()

            store.add(code("B11300001", fall), "Ming", emptyMap())

            assertEquals(kept, file.readText())
            assertEquals(listOf("Ming"), store.nicknames())
        }
    }

    @Test
    fun `a file that cannot be read is not written over`() = runTest {
        val kept = """{"version":1,"owner":null,"people":[]}"""
        file.parentFile!!.mkdirs()
        file.writeText(kept)
        // Skipped where the test runs as a user who reads every file anyway.
        assumeTrue(file.setReadable(false) && !file.canRead())
        try {
            store().add(code("B11300001", fall), "Ming", emptyMap())
        } finally {
            file.setReadable(true)
        }

        assertEquals(kept, file.readText())
    }

    // --- When the store empties ---

    @Test
    fun `signing out empties the store and its file`() = runTest {
        val store = store()
        val ming = store.add(code("B11300001", fall), "Ming", emptyMap())!!
        store.show(ming.id)

        store.clearForSignOut()

        assertEquals(ImportedClassTables(), store.tables.value)
        assertFalse(store.tables.value.hasImportedClassTables)
        assertFalse(file.exists())
        assertTrue(store().load().people.isEmpty())
    }

    @Test
    fun `a sign-out before the store was read still deletes what an earlier run kept`() = runTest {
        store().add(code("B11300001", fall), "Ming", emptyMap())

        store().clearForSignOut()

        assertFalse(file.exists())
    }

    @Test
    fun `another student ID signing in empties the store, and the same one signing in again keeps it`() = runTest {
        val store = store()
        store.add(code("B11300001", fall), "Ming", emptyMap())

        store.accountDidSignIn("B11300000")
        assertEquals(1, store.tables.value.people.size)
        assertEquals(1, store().load().people.size)

        store.accountDidSignIn("B11399999")
        assertTrue(store.tables.value.people.isEmpty())
        assertTrue(store().load().people.isEmpty())
    }

    @Test
    fun `a store owned by one account is read as that account's after a relaunch`() = runTest {
        store().add(code("B11300001", fall), "Ming", emptyMap())

        val relaunched = store()
        relaunched.accountDidSignIn("B11399999")

        assertTrue(relaunched.tables.value.people.isEmpty())
    }

    @Test
    fun `people imported while signed out stay with the first account that signs in`() = runTest {
        account = null
        val store = store()
        store.add(code("B11300001", fall), "Ming", emptyMap())

        store.accountDidSignIn("B11300000")
        assertEquals(1, store.tables.value.people.size)

        store.accountDidSignIn("B11399999")
        assertTrue(store.tables.value.people.isEmpty())
    }

    @Test
    fun `once its last person is deleted, the store belongs to whoever imports next`() = runTest {
        val store = store()
        val ming = store.add(code("B11300001", fall), "Ming", emptyMap())!!
        store.delete(ming.id)

        account = "B11399999"
        store.accountDidSignIn("B11399999")
        store.add(code("B11300002", fall), "Hua", emptyMap())
        store().accountDidSignIn("B11399999")

        assertEquals(listOf("Hua"), store().load().people.map { it.nickname })
    }

    @Test
    fun `an import under another account than the store's drops the earlier account's people`() = runTest {
        val store = store()
        store.add(code("B11300001", fall), "Ming", emptyMap())

        account = "B11399999"
        store.add(code("B11300002", fall), "Hua", emptyMap())

        assertEquals(listOf("Hua"), store.nicknames())
    }
}
