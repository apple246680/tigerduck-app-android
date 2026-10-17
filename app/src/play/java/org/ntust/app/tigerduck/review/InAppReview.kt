package org.ntust.app.tigerduck.review

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import org.ntust.app.tigerduck.util.logSummary
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Play-flavor launcher for Google Play's in-app review (issue #332). When
 * to ask is [ReviewPrompt]'s decision, in `main/`; this only runs the flow
 * Play hands out, over the activity on screen. Whether the dialog actually
 * shows is Play's call — it has quotas of its own — and Play does not say,
 * so a launched flow is the one ask the caller gets.
 *
 * The fdroid variant ships a stub at the same FQN that never launches, so
 * `MainActivity` in `main/` calls this without conditional code — same
 * pattern as `UpdateChecker` and `FcmBootstrap`.
 */
@Singleton
class InAppReview(private val manager: ReviewManager) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(ReviewManagerFactory.create(context))

    /**
     * Requests the flow and starts it over [activity]. True once the flow was
     * started; false when Play would not hand one out — no Play Store, a
     * sideloaded install, offline — so the caller may try at another moment.
     * Nothing here reaches the user as an error: a rating is never worth one.
     */
    suspend fun launch(activity: Activity): Boolean {
        val info = try {
            manager.requestReviewFlow().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "review flow not handed out: ${e.logSummary()}")
            return false
        }
        // Like appUpdateInfo in UpdateChecker, a Play call can throw
        // synchronously against a broken Play install; that is not a launch.
        return runCatching {
            manager.launchReviewFlow(activity, info)
                .addOnFailureListener { Log.w(TAG, "review flow failed: ${it.logSummary()}") }
        }.onFailure { Log.w(TAG, "review flow not launched: ${it.logSummary()}") }.isSuccess
    }

    private companion object {
        const val TAG = "InAppReview"
    }
}
