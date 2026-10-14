package org.ntust.app.tigerduck.network.moodle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.ntust.app.tigerduck.network.MoodleWebserviceError
import org.ntust.app.tigerduck.network.model.moodle.MoodleCourseSummary
import org.ntust.app.tigerduck.network.model.moodle.MoodleDiscussion
import org.ntust.app.tigerduck.network.model.moodle.MoodleForum
import org.ntust.app.tigerduck.network.model.moodle.MoodleGradeItem
import org.ntust.app.tigerduck.network.model.moodle.MoodleMemberList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Moodle calls behind a course's Moodle screens. The live one runs each under the stored
 * token, renewed as for any Moodle call; the demo account has its own, which never leaves the
 * device, and tests pass stand-ins.
 *
 * Phase 2 adds the posts of a discussion, a course's materials, file downloads and view logging
 * here, beside the calls they sit next to on iOS.
 */
interface MoodleCourseApi {
    /** The web-service functions the site allows this account. Each feature hides without its own. */
    suspend fun functions(): Set<String>

    /** The forums of every course in [courseIds], in one call. */
    suspend fun forums(courseIds: List<Int>): List<MoodleForum>

    /** Page [page] (from 0) of forum [forumId]'s discussions, [DISCUSSIONS_PER_PAGE] to a page. */
    suspend fun discussions(forumId: Int, page: Int): List<MoodleDiscussion>

    /**
     * Logs a view of discussion [discussionId], which marks it read. [isCurrent] is asked once the
     * token is in hand: a renewal can outlast a sign-out, and the next account's token would log
     * the view as theirs.
     */
    suspend fun markViewed(discussionId: Int, isCurrent: () -> Boolean)

    /** Everyone in course [courseId] the account can see, with the account's own user id. */
    suspend fun members(courseId: Int): MoodleMemberList

    /** A member's own picture from [url], one [MoodleJson.isOwnPicture] accepts. */
    suspend fun picture(url: String): ByteArray

    /** The signed-in student's grade items in course [courseId], in the gradebook's order. */
    suspend fun gradeItems(courseId: Int): List<MoodleGradeItem>

    suspend fun summary(courseId: Int): MoodleCourseSummary

    /**
     * Where the file at [fileUrl] opens from outside the app, with the account's file key in it;
     * null when it cannot, as for a file off the Moodle site or with no network to ask on.
     * Phase 1 hands this to the browser; phase 2 downloads in the app instead.
     */
    suspend fun fileUrl(fileUrl: String): String?

    companion object {
        const val FORUMS_FUNCTION = "mod_forum_get_forums_by_courses"
        const val DISCUSSIONS_FUNCTION = "mod_forum_get_forum_discussions"
        const val DISCUSSION_POSTS_FUNCTION = "mod_forum_get_discussion_posts"
        const val VIEW_DISCUSSION_FUNCTION = "mod_forum_view_forum_discussion"
        const val MEMBERS_FUNCTION = "core_enrol_get_enrolled_users"
        const val GRADE_ITEMS_FUNCTION = "gradereport_user_get_grade_items"
        const val COURSES_FUNCTION = "core_course_get_courses_by_field"

        /** Asked for explicitly: Moodle's default `perpage` of 0 returns every discussion at once. */
        const val DISCUSSIONS_PER_PAGE = 20

        /** Moodle's error where a course keeps its participants from the account. */
        const val MEMBERS_HIDDEN_CODE = "nopermissions"

        /**
         * What Moodle answers when a course keeps its grade report from the student, by a course
         * setting or a permission: the course has no grades to show, and the fetch did not fail.
         */
        val GRADES_WITHHELD_CODES = setOf("nopermissiontoviewgrades", "nopermissions")

        /**
         * Only what the members screens show, so Moodle sends no other personal data, such as
         * last access times or other courses.
         */
        const val MEMBER_FIELDS =
            "id,fullname,idnumber,department,email,groups,roles,profileimageurl,description,interests,customfields"
    }
}

@Singleton
class LiveMoodleCourseApi @Inject constructor(
    private val webservice: MoodleWebservice,
) : MoodleCourseApi {

    override suspend fun functions(): Set<String> =
        webservice.withToken { webservice.siteInfo(it).functions }

    override suspend fun forums(courseIds: List<Int>): List<MoodleForum> = webservice.withToken { token ->
        MoodleJson.forums(
            webservice.call(
                MoodleCourseApi.FORUMS_FUNCTION,
                token,
                courseIds.mapIndexed { i, id -> "courseids[$i]" to id.toString() },
            ),
        )
    }

    /** `moodlewssettingfilter` runs the message through the site's filters, as the Moodle app asks. */
    override suspend fun discussions(forumId: Int, page: Int): List<MoodleDiscussion> =
        webservice.withToken { token ->
            MoodleJson.discussions(
                webservice.call(
                    MoodleCourseApi.DISCUSSIONS_FUNCTION,
                    token,
                    listOf(
                        "forumid" to forumId.toString(),
                        "page" to page.toString(),
                        "perpage" to MoodleCourseApi.DISCUSSIONS_PER_PAGE.toString(),
                        "moodlewssettingfilter" to "true",
                    ),
                ),
            )
        }

    override suspend fun markViewed(discussionId: Int, isCurrent: () -> Boolean) {
        webservice.withToken { token ->
            if (!isCurrent()) throw CancellationException("signed out")
            webservice.call(
                MoodleCourseApi.VIEW_DISCUSSION_FUNCTION,
                token,
                listOf("discussionid" to discussionId.toString()),
            )
        }
    }

    override suspend fun members(courseId: Int): MoodleMemberList = webservice.withToken { token ->
        val ownId = webservice.siteInfo(token).userId
        val answer = webservice.call(
            MoodleCourseApi.MEMBERS_FUNCTION,
            token,
            listOf(
                "courseid" to courseId.toString(),
                "options[0][name]" to "userfields",
                "options[0][value]" to MoodleCourseApi.MEMBER_FIELDS,
            ),
        )
        MoodleMemberList(ownId = ownId, members = MoodleJson.members(answer))
    }

    /**
     * Under `tokenpluginfile.php` with the account's private access key, as the Moodle app
     * downloads files, else the web-service path with the token. Either key only ever goes to
     * the Moodle site. An answer that is not an image, such as a sign-in page, throws.
     */
    override suspend fun picture(url: String): ByteArray = webservice.withToken { token ->
        val key = webservice.siteInfo(token).privateAccessKey
        val download = MoodleFileUrls.downloadUrl(url, key, token)
        if (!MoodleJson.isOwnPicture(url) || download == null) {
            throw MoodleWebserviceError.MalformedResponse("not a member's picture")
        }
        withContext(Dispatchers.IO) {
            webservice.download(Request.Builder().url(download).get().build()) { type ->
                type?.startsWith("image/") == true
            }
        }
    }

    override suspend fun gradeItems(courseId: Int): List<MoodleGradeItem> = webservice.withToken { token ->
        val userId = webservice.siteInfo(token).userId
        try {
            MoodleJson.gradeItems(
                webservice.call(
                    MoodleCourseApi.GRADE_ITEMS_FUNCTION,
                    token,
                    listOf("courseid" to courseId.toString(), "userid" to userId.toString()),
                ),
                userId,
            )
        } catch (e: MoodleWebserviceError.MalformedResponse) {
            if (e.detail in MoodleCourseApi.GRADES_WITHHELD_CODES) emptyList() else throw e
        }
    }

    override suspend fun fileUrl(fileUrl: String): String? = webservice.withToken { token ->
        MoodleFileUrls.downloadUrl(fileUrl, webservice.siteInfo(token).privateAccessKey, token)
    }

    override suspend fun summary(courseId: Int): MoodleCourseSummary = webservice.withToken { token ->
        MoodleJson.summary(
            webservice.call(
                MoodleCourseApi.COURSES_FUNCTION,
                token,
                listOf(
                    "field" to "id",
                    "value" to courseId.toString(),
                    "moodlewssettingfilter" to "true",
                ),
            ),
            courseId,
        )
    }
}
