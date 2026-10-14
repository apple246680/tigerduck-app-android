package org.ntust.app.tigerduck.ui.screen.coursemoodle

/**
 * The course detail and the Moodle screens it opens, each a page of the app's own navigation, so
 * back, predictive back and the School Mail compose a member's email opens all stack as any
 * other page does. Phase 2 adds materials, forums and threads here.
 */
object MoodleRoutes {
    /** The course detail of the class table's selected course. */
    const val COURSE_DETAIL = "classTable/course"

    const val ANNOUNCEMENTS = "courseMoodle/{courseId}/announcements"
    const val ANNOUNCEMENT = "courseMoodle/{courseId}/announcements/{discussionId}"
    const val GRADES = "courseMoodle/{courseId}/grades"
    const val MEMBERS = "courseMoodle/{courseId}/members"
    const val MEMBER = "courseMoodle/{courseId}/members/{memberId}"

    const val ARG_DISCUSSION_ID = "discussionId"
    const val ARG_MEMBER_ID = "memberId"

    fun announcements(courseId: Int) = "courseMoodle/$courseId/announcements"
    fun announcement(courseId: Int, discussionId: Int) = "courseMoodle/$courseId/announcements/$discussionId"
    fun grades(courseId: Int) = "courseMoodle/$courseId/grades"
    fun members(courseId: Int) = "courseMoodle/$courseId/members"
    fun member(courseId: Int, memberId: Int) = "courseMoodle/$courseId/members/$memberId"
}
