// The rules the Moodle screens follow, kept apart from the screens and the store so they can be
// tested on the JVM: which section rows show, the order of a forum's discussions, the role
// sections of the members list, and the grades a course total and its items show.

package org.ntust.app.tigerduck.data.moodle

import org.ntust.app.tigerduck.network.moodle.MoodleCourseApi
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseGrades
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseSummary
import org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussion
import org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussionList
import org.ntust.app.tigerduck.network.model.moodle.MoodleForum
import org.ntust.app.tigerduck.network.model.moodle.MoodleGradeItem
import org.ntust.app.tigerduck.network.model.moodle.MoodleMember
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberList
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberRole
import org.ntust.app.tigerduck.network.model.moodle.MoodleTermForums
import java.text.Collator
import java.util.Locale

/** A row of the course detail's Moodle section. Materials join in phase 2. */
data class MoodleSectionRow(val kind: Kind, val count: Int, val hasUnread: Boolean) {
    enum class Kind { ANNOUNCEMENTS, GRADES, MEMBERS, FORUMS }
}

object MoodleSections {

    /** [term]'s forums of course [courseId]; none while the copy does not cover the course. */
    fun courseForums(term: MoodleTermForums?, courseId: Int): List<MoodleForum> {
        if (term == null || courseId !in term.courseIds) return emptyList()
        return term.forums.filter { it.courseId == courseId }
    }

    fun newsForum(term: MoodleTermForums?, courseId: Int): MoodleForum? =
        courseForums(term, courseId).firstOrNull { it.isNews }

    /** The course's other forums that have something in them. */
    fun generalForums(term: MoodleTermForums?, courseId: Int): List<MoodleForum> =
        courseForums(term, courseId).filter { !it.isNews && it.discussionCount > 0 }

    /**
     * The section's rows, in its order; a kind with nothing to count has no row. Forums show in
     * phase 2, with their screens, so [includeForums] is off until then.
     */
    fun rows(
        term: MoodleTermForums?,
        courseId: Int,
        grades: MoodleCourseGrades?,
        members: MoodleMemberList?,
        includeForums: Boolean = false,
    ): List<MoodleSectionRow> {
        val rows = mutableListOf<MoodleSectionRow>()
        newsForum(term, courseId)?.takeIf { it.discussionCount > 0 }?.let {
            rows += MoodleSectionRow(MoodleSectionRow.Kind.ANNOUNCEMENTS, it.discussionCount, it.unreadPostCount > 0)
        }
        val graded = grades?.let(MoodleGrades::graded)?.size ?: 0
        if (graded > 0) rows += MoodleSectionRow(MoodleSectionRow.Kind.GRADES, graded, false)
        if (members != null && MoodleMembers.showsOthers(members)) {
            rows += MoodleSectionRow(MoodleSectionRow.Kind.MEMBERS, members.members.size, false)
        }
        if (includeForums) {
            val general = generalForums(term, courseId)
            if (general.isNotEmpty()) {
                rows += MoodleSectionRow(
                    MoodleSectionRow.Kind.FORUMS,
                    general.sumOf { it.discussionCount },
                    general.any { it.unreadPostCount > 0 },
                )
            }
        }
        return rows
    }

    /**
     * Whether a summary says something the course detail does not: it has a letter or Han
     * character, and it is not just a name of the course or one of [codes], ignoring case and
     * spaces. An NTUST course's summary can be its idnumber and nothing else.
     */
    fun isWorthShowing(summary: MoodleCourseSummary, codes: List<String>): Boolean {
        if (summary.text.none { it.isLetter() }) return false
        val text = comparable(summary.text)
        return (summary.names + codes).none { comparable(it) == text }
    }

    private fun comparable(text: String): String = text.filterNot { it.isWhitespace() }.lowercase()
}

object MoodleDiscussions {

    /** Pinned first, then newest first by [byModified]'s date: the latest post's, else the posting time. */
    fun sorted(list: MoodleDiscussionList, byModified: Boolean = false): List<MoodleDiscussion> =
        list.discussions.sortedWith(
            compareByDescending<MoodleDiscussion> { it.isPinned }
                .thenByDescending { if (byModified) it.modified else it.created }
                .thenByDescending { it.id },
        )

    /** [list] with [page] added: discussions already listed are not listed twice. */
    fun append(list: MoodleDiscussionList, page: List<MoodleDiscussion>): MoodleDiscussionList {
        val known = list.discussions.map { it.id }.toSet()
        return list.copy(
            discussions = list.discussions + page.filter { it.id !in known },
            pageCount = list.pageCount + 1,
            hasMore = page.size == MoodleCourseApi.DISCUSSIONS_PER_PAGE,
        )
    }

    fun empty(forumId: Int) = MoodleDiscussionList(forumId, emptyList(), pageCount = 0, hasMore = false)
}

object MoodleGrades {
    fun total(grades: MoodleCourseGrades): MoodleGradeItem? =
        grades.items.firstOrNull { it.isCourseTotal && it.isShown }

    /** What the grades screen lists and its row counts: graded and shown, without totals. */
    fun graded(grades: MoodleCourseGrades): List<MoodleGradeItem> =
        grades.items.filter { !it.isCourseTotal && !it.isCategoryTotal && it.isShown }

    private val NUMBER = Regex("-?[0-9]+([.,][0-9]+)?")

    /**
     * The most the grade could be, written only beside a grade that is a number: over a letter, a
     * percentage or a scale's word, the maximum would misread. Its digits follow Moodle's,
     * whatever the device's region.
     */
    fun maxText(item: MoodleGradeItem): String? {
        val max = item.maxGrade ?: return null
        val grade = item.grade ?: return null
        if (!NUMBER.matches(grade)) return null
        val rounded = Math.round(max * 100) / 100.0
        return if (rounded == Math.floor(rounded)) {
            rounded.toLong().toString()
        } else {
            String.format(Locale.ROOT, "%.2f", rounded).trimEnd('0').trimEnd('.')
        }
    }

    private val MODULE = Regex("[a-z0-9_]+")

    /** The item's activity page, `/mod/<module>/view.php?id=<cmid>`; null for one no activity gives. */
    fun activityPath(item: MoodleGradeItem): String? {
        val module = item.module ?: return null
        val cmid = item.cmid ?: return null
        if (!MODULE.matches(module)) return null
        return "/mod/$module/view.php?id=$cmid"
    }
}

/** A Moodle user as the app names them: NTUST's Moodle spells a full name "student ID @ name". */
object MoodlePersonName {
    fun displayName(fullName: String): String {
        val trimmed = fullName.trim()
        val at = trimmed.indexOf('@')
        if (at < 0) return trimmed
        return trimmed.substring(at + 1).trim().ifEmpty { trimmed }
    }

    /** The part before the `@`, for a user whose `idnumber` Moodle leaves out. */
    fun studentId(fullName: String): String? {
        val at = fullName.indexOf('@')
        if (at < 0) return null
        return fullName.substring(0, at).trim().ifEmpty { null }
    }
}

val MoodleMember.name: String get() = MoodlePersonName.displayName(fullName)
val MoodleMember.studentId: String? get() = idNumber ?: MoodlePersonName.studentId(fullName)

/** Where a member is listed. A role the app does not know goes after the assistants, under Moodle's name for it. */
sealed class MoodleMemberPlacement : Comparable<MoodleMemberPlacement> {
    data object Instructors : MoodleMemberPlacement()
    data object Assistants : MoodleMemberPlacement()
    data class Other(val sortOrder: Int, val name: String) : MoodleMemberPlacement()
    data object Auditors : MoodleMemberPlacement()
    data object Students : MoodleMemberPlacement()

    private val rank: Int
        get() = when (this) {
            Instructors -> 0
            Assistants -> 1
            is Other -> 2
            Auditors -> 3
            Students -> 4
        }

    override fun compareTo(other: MoodleMemberPlacement): Int {
        if (rank != other.rank) return rank.compareTo(other.rank)
        if (this is Other && other is Other) {
            return compareValuesBy(this, other, { it.sortOrder }, { it.name })
        }
        return 0
    }

    /** Staff by name; auditors and students by student ID, as a class list runs. */
    val listsByStudentId: Boolean get() = this == Auditors || this == Students

    companion object {
        fun of(role: MoodleMemberRole): MoodleMemberPlacement = when (role.shortName) {
            "editingteacher" -> Instructors
            "teacher" -> Assistants
            "auditor_student" -> Auditors
            "student" -> Students
            else -> Other(role.sortOrder, role.name.ifEmpty { role.shortName })
        }

        /**
         * A person with several roles shows once, in the first section of them. One with none is
         * listed with the students, as Moodle counts every enrolled person a participant.
         */
        fun of(member: MoodleMember): MoodleMemberPlacement =
            member.roles.map(::of).minOrNull() ?: Students
    }
}

data class MoodleMemberSection(val placement: MoodleMemberPlacement, val members: List<MoodleMember>)

object MoodleMembers {

    /** Whether anyone but the account is there to show, which a course split into groups may not have. */
    fun showsOthers(list: MoodleMemberList): Boolean = list.members.any { it.id != list.ownId }

    /** The search runs here, never on Moodle, over what a row shows. */
    fun sections(members: List<MoodleMember>, query: String = "", locale: Locale = Locale.getDefault()): List<MoodleMemberSection> {
        val needle = query.trim()
        val shown = if (needle.isEmpty()) members else members.filter { matches(it, needle) }
        val collator = Collator.getInstance(locale)
        val byName = Comparator<MoodleMember> { a, b ->
            collator.compare(a.name, b.name).takeIf { it != 0 } ?: a.id.compareTo(b.id)
        }
        // A person without a student ID comes after those with one.
        val byStudentId = Comparator<MoodleMember> { a, b ->
            val left = a.studentId
            val right = b.studentId
            when {
                left != null && right != null && left != right -> left.compareTo(right)
                left != null && right == null -> -1
                left == null && right != null -> 1
                else -> byName.compare(a, b)
            }
        }
        return shown.groupBy(MoodleMemberPlacement::of)
            .toSortedMap()
            .map { (placement, people) ->
                MoodleMemberSection(placement, people.sortedWith(if (placement.listsByStudentId) byStudentId else byName))
            }
    }

    private fun matches(member: MoodleMember, needle: String): Boolean =
        listOfNotNull(member.name, member.studentId, member.department)
            .any { it.contains(needle, ignoreCase = true) }
}
