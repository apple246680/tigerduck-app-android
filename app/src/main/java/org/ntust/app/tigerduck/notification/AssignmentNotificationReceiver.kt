package org.ntust.app.tigerduck.notification

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.di.ApplicationScope
import org.ntust.app.tigerduck.util.logSummary
import javax.inject.Inject

@AndroidEntryPoint
class AssignmentNotificationReceiver : BroadcastReceiver() {

    @Inject
    lateinit var appPreferences: AppPreferences

    @Inject
    lateinit var notificationChannels: NotificationChannelRegistrar

    @Inject
    lateinit var reminderCheck: AssignmentReminderCheck

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        val courseName = intent.getStringExtra(EXTRA_COURSE_NAME) ?: ""
        val assignmentId = intent.getStringExtra(EXTRA_ASSIGNMENT_ID) ?: return
        // Armed by an older version for homework the user had ignored or
        // marked done, which gets no reminder now. Alarms outlive an update.
        if (intent.getIntExtra(EXTRA_KIND, 0) == LEGACY_KIND_SAFETY_NET) return
        val offset = AssignmentReminderOffset.fromRawValue(intent.getStringExtra(EXTRA_OFFSET))
        val armedDueAtMs =
            if (intent.hasExtra(EXTRA_DUE_AT)) intent.getLongExtra(EXTRA_DUE_AT, 0L) else null

        // Every path that turns reminders off cancels the alarms already
        // armed, but one armed in a race with that cancel would still fire
        // here. The switch has the last word, so it is read again at fire time.
        if (!appPreferences.notifyAssignments) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        // The assignment may have been handed in since this was armed: on the
        // web, on another device or by a teammate. Shown only when the cache
        // and Moodle both still have it due; anything else drops it.
        val pending = goAsync()
        appScope.launch(Dispatchers.IO) {
            try {
                val verdict = withTimeoutOrNull(CHECK_BUDGET_MS) {
                    reminderCheck.verdict(assignmentId, armedDueAtMs)
                } ?: ReminderVerdict.Drop(ReminderDropReason.TIMED_OUT)
                if (verdict is ReminderVerdict.Drop) {
                    Log.i(TAG, "reminder dropped: ${verdict.reason}")
                } else {
                    notificationChannels.ensureRegistered()
                    post(context, title, courseName, assignmentId, offset)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "reminder check failed: ${e.logSummary()}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val CHANNEL_ID = NotificationChannels.ASSIGNMENT_DUE
        const val EXTRA_TITLE = "title"
        const val EXTRA_COURSE_NAME = "course_name"
        const val EXTRA_ASSIGNMENT_ID = "assignment_id"

        private const val EXTRA_KIND = "kind"
        private const val LEGACY_KIND_SAFETY_NET = 1

        private const val TAG = "AssignmentReminder"

        // goAsync() work should end within about 10 s, and an allow-while-idle
        // alarm lets the app use the network in Doze for about as long.
        private const val CHECK_BUDGET_MS = 8_000L

        // rawValue of the AssignmentReminderOffset that produced this
        // reminder; null on legacy single-shot intents pending from before
        // per-offset scheduling shipped.
        const val EXTRA_OFFSET = "offset"

        // Epoch milliseconds of the due date the reminder was armed for;
        // absent on alarms armed by a version without it.
        const val EXTRA_DUE_AT = "due_at"

        /**
         * Post the reminder, past every check onReceive makes first; the
         * developer menu's preview posts through here too. The channels must
         * already exist.
         */
        fun post(
            context: Context,
            title: String,
            courseName: String,
            assignmentId: String,
            offset: AssignmentReminderOffset?,
        ) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val (titleText, bodyText) = if (offset != null) {
                // Per-offset wording (mirrors iOS). `notification_assignment_reminder_title`
                // takes the course name; the body templates take (course, title).
                context.getString(R.string.notification_assignment_reminder_title, courseName) to
                    context.getString(offset.bodyRes, courseName, title)
            } else {
                // Legacy single-shot reminder (pre-upgrade pending alarms with no
                // EXTRA_OFFSET) keeps the original "due soon" wording so users
                // who upgrade mid-week still see something coherent for alarms
                // we didn't get a chance to re-arm.
                context.getString(R.string.notification_assignment_due_title) to
                    context.getString(R.string.notification_assignment_due_body, courseName, title)
            }

            val notification = context.brandedNotification(CHANNEL_ID)
                .setContentTitle(titleText)
                .setContentText(bodyText)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setGroup(NotificationGroup.ASSIGNMENT.key)
                .build()

            // Unique per (assignment × offset) so reminders for the same
            // assignment don't collapse into one another in the shade.
            val notifId = (assignmentId + "::" + (offset?.rawValue ?: "legacy")).hashCode() and 0x7FFFFFFF
            notificationManager.notify(notifId, notification)
            NotificationGroup.ASSIGNMENT.postSummary(context)
        }
    }
}
