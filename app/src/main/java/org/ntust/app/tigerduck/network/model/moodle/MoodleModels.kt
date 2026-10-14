// A course's data on Moodle, as the course detail's Moodle screens show it and as
// DataCache keeps it per account (see DataCache.saveMoodleData).
//
// Persisted with Gson, so the upgrade-safe-persistence rules apply: Gson builds these
// through Unsafe, skipping constructors and Kotlin defaults. They live only in the
// Moodle cache, which is versioned as a whole: ANY change to a field here (added,
// removed, renamed, retyped) must bump DataCache.MOODLE_CACHE_SCHEMA, which drops every
// copy written in the old shape. The data comes back on the next fetch, so nothing is
// lost but a moment offline. No field carries a Kotlin default for the same reason.
//
// Times are Unix seconds, as Moodle sends them.

package org.ntust.app.tigerduck.network.model.moodle

/**
 * A course's forum, from `mod_forum_get_forums_by_courses`. Every course has one `news` forum,
 * which holds its announcements.
 */
data class MoodleForum(
    val id: Int,
    val courseId: Int,
    val type: String,
    val name: String,
    /** The course module, which Moodle's forum page takes as `id`. */
    val cmid: Int,
    val discussionCount: Int,
    val unreadPostCount: Int,
    /** The intro as one line of text; null without one. */
    val intro: String?,
) {
    val isNews: Boolean get() = type == "news"
}

/** A file as Moodle's `external_files` lists it. */
data class MoodleFile(
    val name: String,
    val size: Long,
    val url: String,
    val mimeType: String?,
    /** Unix seconds; null when Moodle sends none. */
    val modified: Long?,
    /** The folder it is in, such as `/week1/`, which tells apart two files of one name. */
    val path: String?,
)

/**
 * A discussion as `mod_forum_get_forum_discussions` lists it: its first post, with the
 * discussion's counts.
 */
data class MoodleDiscussion(
    /** The discussion, which `mod_forum_view_forum_discussion` and `discuss.php?d=` take. */
    val id: Int,
    val postId: Int,
    val title: String,
    /** HTML, already through Moodle's text filters. */
    val message: String,
    /** The message as one line of text, kept with it so a list does not parse HTML as it draws. */
    val preview: String,
    val created: Long,
    /** The latest post's time. */
    val modified: Long,
    val isPinned: Boolean,
    val unreadCount: Int,
    val replyCount: Int,
    /** Null where the forum hides authors. */
    val authorName: String?,
    /** The author's Moodle user, which finds them among the course's members. */
    val authorId: Int?,
    val attachments: List<MoodleFile>,
)

/**
 * A person in a course, from `core_enrol_get_enrolled_users`, as the course's other members may
 * see them. Every field but the id and the name is missing where Moodle hides it.
 */
data class MoodleMember(
    val id: Int,
    /** As Moodle spells it, which at NTUST is "student ID @ name". */
    val fullName: String,
    val idNumber: String?,
    val department: String?,
    val email: String?,
    val groups: List<String>,
    val roles: List<MoodleMemberRole>,
    /** The member's own picture; null for Moodle's default one, which is never downloaded. */
    val pictureUrl: String?,
    /** The profile description as plain text, empty when there is none. */
    val description: String,
    val interests: List<String>,
    /** The custom profile fields Moodle shows course members, by Moodle's own label. */
    val fields: List<MoodleMemberField>,
)

data class MoodleMemberRole(
    val shortName: String,
    /** Empty for Moodle's standard roles, which take their names from Moodle's language pack. */
    val name: String,
    val sortOrder: Int,
)

data class MoodleMemberField(
    val name: String,
    val value: String,
)

/**
 * One line of a course's gradebook as `gradereport_user_get_grade_items` shows it to the
 * signed-in student: an activity's grade, a category's subtotal or the course total.
 */
data class MoodleGradeItem(
    val id: Int,
    /** Empty for the course total and a category's subtotal, which Moodle leaves unnamed. */
    val name: String,
    /** `course`, `category`, `mod` for an activity, `manual` for an item the teacher added. */
    val type: String,
    /** The activity's module, such as `assign` or `quiz`. */
    val module: String?,
    /** The activity's course module, which its page takes as `id`. */
    val cmid: Int?,
    /** The grade as the course's settings write it; null while there is none to show. */
    val grade: String?,
    val maxGrade: Double?,
    val isHidden: Boolean,
    /** The teacher's feedback as Markdown; empty without any. */
    val feedback: String,
) {
    val isCourseTotal: Boolean get() = type == "course"
    val isCategoryTotal: Boolean get() = type == "category"
    val isShown: Boolean get() = grade != null && !isHidden
}

/**
 * A course's summary on Moodle, with the course's names there, which tell a summary of its own
 * from one that only repeats them.
 */
data class MoodleCourseSummary(
    /** HTML, already through Moodle's text filters. */
    val html: String,
    /** The summary as one line of text. */
    val text: String,
    val names: List<String>,
) {
    companion object {
        /** A course without a summary, or on a site that does not allow the call. */
        val EMPTY = MoodleCourseSummary("", "", emptyList())
    }
}

/** The forums of a term's courses, from one call for all of them. */
data class MoodleTermForums(
    /** The courses asked about, so a course with no forums tells apart from one not asked about. */
    val courseIds: List<Int>,
    val forums: List<MoodleForum>,
)

/** The discussions of one forum, a page at a time. */
data class MoodleDiscussionList(
    val forumId: Int,
    val discussions: List<MoodleDiscussion>,
    /** Pages fetched; a refresh fetches as many again, so the list keeps its length. */
    val pageCount: Int,
    val hasMore: Boolean,
)

/** The people of a course the account can see, which take in the account itself. */
data class MoodleMemberList(
    /** The account's own Moodle user; null when Moodle was not asked. */
    val ownId: Int?,
    val members: List<MoodleMember>,
)

/** A course's gradebook for the signed-in student, in Moodle's order. */
data class MoodleCourseGrades(
    val items: List<MoodleGradeItem>,
)
