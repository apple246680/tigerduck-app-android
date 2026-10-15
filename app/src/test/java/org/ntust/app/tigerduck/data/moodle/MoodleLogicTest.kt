package org.ntust.app.tigerduck.data.moodle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseGrades
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseSummary
import org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussionList
import org.ntust.app.tigerduck.network.model.moodle.MoodleGradeItem
import org.ntust.app.tigerduck.network.model.moodle.MoodleMember
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberList
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberRole
import org.ntust.app.tigerduck.network.model.moodle.MoodleTermForums
import java.util.Locale

class MoodleLogicTest {

    private fun grade(id: Int, type: String = "mod", grade: String? = "90", hidden: Boolean = false, max: Double? = 100.0) =
        MoodleGradeItem(id, "Item $id", type, "assign", 30 + id, grade, max, hidden, "")

    private fun member(id: Int, fullName: String, vararg roles: String, idNumber: String? = null) = MoodleMember(
        id = id, fullName = fullName, idNumber = idNumber, department = null, email = null,
        groups = emptyList(), roles = roles.map { MoodleMemberRole(it, "", 0) }, pictureUrl = null,
        description = "", interests = emptyList(), fields = emptyList(),
    )

    @Test
    fun `a section row shows only for a kind with something in it`() {
        val term = MoodleTermForums(
            listOf(5, 6),
            listOf(
                testForum(id = 1, courseId = 5, type = "news", count = 3, unread = 2),
                testForum(id = 2, courseId = 6, type = "news", count = 9, unread = 0),
            ),
        )
        val grades = MoodleCourseGrades(listOf(grade(1), grade(2, grade = null), grade(3, type = "course")))
        val onlyMe = MoodleMemberList(ownId = 1, members = listOf(member(1, "Me")))

        val rows = MoodleSections.rows(term, courseId = 5, grades = grades, members = onlyMe)

        assertEquals(
            listOf(
                MoodleSectionRow(MoodleSectionRow.Kind.ANNOUNCEMENTS, 3, hasUnread = true),
                MoodleSectionRow(MoodleSectionRow.Kind.GRADES, 1, hasUnread = false),
            ),
            rows,
        )
        assertTrue("a term copy that does not cover the course shows nothing",
            MoodleSections.rows(term, courseId = 7, grades = null, members = null).isEmpty())
    }

    @Test
    fun `a course with a total but no graded activity shown keeps its grades row`() {
        val onlyTotal = MoodleCourseGrades(listOf(grade(2, grade = null), grade(3, type = "course")))

        assertEquals(
            MoodleSectionRow(MoodleSectionRow.Kind.GRADES, 0, false),
            MoodleSections.rows(null, 5, onlyTotal, null).single(),
        )
        assertTrue(
            "nothing shown, total included, has no row",
            MoodleSections.rows(null, 5, MoodleCourseGrades(listOf(grade(3, grade = null, type = "course"))), null).isEmpty(),
        )
    }

    @Test
    fun `the members row needs someone besides the account`() {
        val list = MoodleMemberList(ownId = 1, members = listOf(member(1, "Me"), member(2, "You")))
        val rows = MoodleSections.rows(null, 5, null, list)
        assertEquals(MoodleSectionRow(MoodleSectionRow.Kind.MEMBERS, 2, false), rows.single())
    }

    @Test
    fun `a summary that only repeats the course's names or codes is not shown`() {
        val names = listOf("資料結構 Data Structures")
        assertFalse(MoodleSections.isWorthShowing(MoodleCourseSummary("", "1151CS3005301", emptyList()), listOf("1151CS3005301")))
        assertFalse(MoodleSections.isWorthShowing(MoodleCourseSummary("", "資料結構  data structures", names), emptyList()))
        assertFalse("digits only", MoodleSections.isWorthShowing(MoodleCourseSummary("", "2026", emptyList()), emptyList()))
        assertTrue(MoodleSections.isWorthShowing(MoodleCourseSummary("", "Weekly quizzes", names), listOf("CS3005301")))
    }

    @Test
    fun `discussions sort pinned first, then newest`() {
        val list = MoodleDiscussionList(
            1,
            listOf(
                testDiscussion(1, created = 100),
                testDiscussion(2, created = 300),
                testDiscussion(3, created = 50, pinned = true),
            ),
            pageCount = 1, hasMore = false,
        )
        assertEquals(listOf(3, 2, 1), MoodleDiscussions.sorted(list).map { it.id })
    }

    @Test
    fun `a full page means there may be more, and a repeat is not listed twice`() {
        val first = MoodleDiscussions.append(MoodleDiscussions.empty(1), (1..20).map { testDiscussion(it) })
        assertTrue(first.hasMore)
        assertEquals(1, first.pageCount)
        val second = MoodleDiscussions.append(first, listOf(testDiscussion(20), testDiscussion(21)))
        assertFalse(second.hasMore)
        assertEquals(21, second.discussions.size)
    }

    @Test
    fun `members group by role, staff by name and students by ID`() {
        val members = listOf(
            member(1, "B2 @ Zed", "student", idNumber = "B2"),
            member(2, "B1 @ Amy", "student", idNumber = "B1"),
            member(3, "Ng", "editingteacher"),
            member(4, "Lee", "teacher"),
            member(5, "Ann", "editingteacher", "student"),
            member(6, "Odd", "observer"),
            member(7, "No role"),
        )
        val sections = MoodleMembers.sections(members, locale = Locale.ENGLISH)

        assertEquals(
            listOf(
                MoodleMemberPlacement.Instructors,
                MoodleMemberPlacement.Assistants,
                MoodleMemberPlacement.Other(0, "observer"),
                MoodleMemberPlacement.Students,
            ),
            sections.map { it.placement },
        )
        assertEquals("a teacher who is also a student shows once, as a teacher",
            listOf(5, 3), sections[0].members.map { it.id })
        assertEquals("students by ID, one without after", listOf(2, 1, 7), sections[3].members.map { it.id })
    }

    @Test
    fun `the search matches name, student ID or department`() {
        val members = listOf(member(1, "B11300001 @ Amy", "student"), member(2, "B11300002 @ Bob", "student"))
        assertEquals(listOf(2), MoodleMembers.sections(members, "bob").flatMap { it.members }.map { it.id })
        assertEquals(listOf(1), MoodleMembers.sections(members, "00001").flatMap { it.members }.map { it.id })
        assertTrue(MoodleMembers.sections(members, "zzz").isEmpty())
    }

    @Test
    fun `names read from NTUST's ID @ name form`() {
        assertEquals("Amy", MoodlePersonName.displayName("B11300001 @ Amy"))
        assertEquals("B11300001", MoodlePersonName.studentId("B11300001 @ Amy"))
        assertEquals("Teacher Wang", MoodlePersonName.displayName("Teacher Wang"))
        assertNull(MoodlePersonName.studentId("Teacher Wang"))
    }

    @Test
    fun `the maximum shows only beside a number`() {
        assertEquals("100", MoodleGrades.maxText(grade(1, grade = "95.00")))
        assertEquals("12.5", MoodleGrades.maxText(grade(1, grade = "10", max = 12.5)))
        assertNull(MoodleGrades.maxText(grade(1, grade = "A+")))
        assertNull(MoodleGrades.maxText(grade(1, grade = "95 %")))
    }

    @Test
    fun `an item from an activity opens it, a manual one has nothing to open`() {
        assertEquals("/mod/assign/view.php?id=31", MoodleGrades.activityPath(grade(1)))
        assertNull(MoodleGrades.activityPath(grade(1).copy(module = null)))
        assertNull(MoodleGrades.activityPath(grade(1).copy(module = "../x")))
    }

    @Test
    fun `groups are listed with the language's own separator`() {
        assertEquals("A 組、B 組", MoodleMembers.groupsText(listOf("A 組", "B 組"), Locale.TRADITIONAL_CHINESE))
        assertEquals("Team A, Team B", MoodleMembers.groupsText(listOf("Team A", "Team B"), Locale.ENGLISH))
    }
}
