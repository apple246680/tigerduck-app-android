package org.ntust.app.tigerduck.ui.screen.coursemoodle

/**
 * The course detail and the Moodle screens it opens, each a page of the app's own navigation, so
 * back, predictive back and the School Mail compose a member's email opens all stack as any
 * other page does.
 */
object MoodleRoutes {
    /** The course detail of the class table's selected course. */
    const val COURSE_DETAIL = "classTable/course"

    /** What every Moodle page's route starts with. */
    const val PREFIX = "courseMoodle/"

    const val ANNOUNCEMENTS = "courseMoodle/{courseId}/announcements"
    const val ANNOUNCEMENT = "courseMoodle/{courseId}/announcements/{discussionId}"
    const val FORUMS = "courseMoodle/{courseId}/forums"
    const val FORUM = "courseMoodle/{courseId}/forums/{forumId}"
    const val DISCUSSION = "courseMoodle/{courseId}/forums/{forumId}/discussions/{discussionId}"
    const val GRADES = "courseMoodle/{courseId}/grades"
    const val MEMBERS = "courseMoodle/{courseId}/members"
    const val MEMBER = "courseMoodle/{courseId}/members/{memberId}"

    const val ARG_DISCUSSION_ID = "discussionId"
    const val ARG_FORUM_ID = "forumId"
    const val ARG_MEMBER_ID = "memberId"

    fun announcements(courseId: Int) = "courseMoodle/$courseId/announcements"
    fun announcement(courseId: Int, discussionId: Int) = "courseMoodle/$courseId/announcements/$discussionId"
    fun forums(courseId: Int) = "courseMoodle/$courseId/forums"
    fun forum(courseId: Int, forumId: Int) = "courseMoodle/$courseId/forums/$forumId"
    fun discussion(courseId: Int, forumId: Int, discussionId: Int) =
        "courseMoodle/$courseId/forums/$forumId/discussions/$discussionId"
    fun grades(courseId: Int) = "courseMoodle/$courseId/grades"
    fun members(courseId: Int) = "courseMoodle/$courseId/members"
    fun member(courseId: Int, memberId: Int) = "courseMoodle/$courseId/members/$memberId"
}
