package org.ntust.app.tigerduck.review

import android.app.Activity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * F-Droid stub. That build has no Play Store to rate the app on, so there is
 * no review flow to launch; false, so [ReviewPrompt] records nothing as
 * asked. Same FQN as the play-flavor implementation so `MainActivity` in
 * `main/` can inject and call it regardless of which flavor is being built
 * — same pattern as `UpdateChecker`. No GMS dependencies, so this compiles
 * on fdroid.
 */
@Singleton
class InAppReview @Inject constructor() {
    suspend fun launch(activity: Activity): Boolean = false
}
