package org.ntust.app.tigerduck.ui.screen.coursemoodle

/**
 * The course detail and the Moodle screens it opens, each a page of the course detail sheet's own
 * navigation (see CourseDetailSheet), so back, predictive back and the School Mail compose a
 * member's email opens all stack inside the sheet. Phase 2 adds materials, forums and threads here.
 */
object MoodleRoutes {
    /** The course detail of the class table's selected course, where the sheet's navigation starts. */
    const val COURSE_DETAIL = "classTable/course"

    const val ANNOUNCEMENTS = "courseMoodle/{courseId}/announcements"
    const val ANNOUNCEMENT = "courseMoodle/{courseId}/announcements/{discussionId}"
    const val ASSIGNMENTS = "courseMoodle/{courseId}/assignments"

    /** An assignment's page inside the sheet; `courseId` is 0 for one whose course the cache does not name. */
    const val ASSIGNMENT = "courseMoodle/{courseId}/assignments/{assignmentId}"
    const val GRADES = "courseMoodle/{courseId}/grades"
    const val MEMBERS = "courseMoodle/{courseId}/members"
    const val MEMBER = "courseMoodle/{courseId}/members/{memberId}"

    const val ARG_DISCUSSION_ID = "discussionId"
    const val ARG_ASSIGNMENT_ID = "assignmentId"
    const val ARG_MEMBER_ID = "memberId"

    fun announcements(courseId: Int) = "courseMoodle/$courseId/announcements"
    fun announcement(courseId: Int, discussionId: Int) = "courseMoodle/$courseId/announcements/$discussionId"
    fun assignments(courseId: Int) = "courseMoodle/$courseId/assignments"
    fun assignment(courseId: Int, assignmentId: String) = "courseMoodle/$courseId/assignments/$assignmentId"
    fun grades(courseId: Int) = "courseMoodle/$courseId/grades"
    fun members(courseId: Int) = "courseMoodle/$courseId/members"
    fun member(courseId: Int, memberId: Int) = "courseMoodle/$courseId/members/$memberId"
}
