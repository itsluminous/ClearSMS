package app.clearsms.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.clearsms.R
import app.clearsms.data.db.MessageEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notifies the user about FRESH messages surfaced by a catch-up import -
 * messages newer than anything the app had seen before the run, i.e.
 * messages that never produced a live-delivery notification (they landed in
 * the provider while another app was default, or the receiver's Room insert
 * lost the race to the import).
 *
 * Up to [MAX_INDIVIDUAL] fresh messages are routed one-by-one through
 * [IncomingMessageRouter] - the exact live-delivery pipeline, so OTP /
 * transaction / scam routing, every settings gate, the notification ids and
 * therefore read-cancellation all behave identically. Beyond the cap a
 * single summary ("N new messages") posts instead: after a long outage the
 * shade must not be stormed with dozens of entries (Android also
 * rate-limits bursts, silently dropping the excess - a storm would lose
 * exactly the messages it tries to surface). Five keeps individually
 * actionable notifications (copy OTP, mark read) for the common short gap.
 *
 * Old history (at or below the watermark) never reaches this class: the
 * initial onboarding import stays silent end-to-end.
 */
@Singleton
class CatchUpNotifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val router: IncomingMessageRouter,
    ) {
        /** Routes [freshMessages] individually, or posts one summary of [freshCount]. */
        suspend fun notifyFresh(
            freshMessages: List<MessageEntity>,
            freshCount: Int,
        ) {
            when {
                freshCount == 0 -> Unit
                freshCount <= MAX_INDIVIDUAL -> freshMessages.forEach { router.route(it) }
                else -> postSummary(freshCount)
            }
        }

        private fun postSummary(count: Int) {
            Channels.ensureCreated(context)
            val intent =
                Intent()
                    .setClassName(context, "app.clearsms.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val contentIntent =
                PendingIntent.getActivity(
                    context,
                    NotificationIds.CATCH_UP_SUMMARY,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            val notification =
                NotificationCompat
                    .Builder(context, Channels.MESSAGES)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(
                        context.resources.getQuantityString(R.plurals.catch_up_summary_title, count, count),
                    ).setContentText(context.getString(R.string.catch_up_summary_text))
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setContentIntent(contentIntent)
                    .setAutoCancel(true)
                    .build()
            try {
                NotificationManagerCompat.from(context).notify(NotificationIds.CATCH_UP_SUMMARY, notification)
            } catch (_: SecurityException) {
                // POST_NOTIFICATIONS not granted; messages are still visible in-app.
            }
        }

        companion object {
            /** Per-message notifications up to here; a single summary beyond. */
            const val MAX_INDIVIDUAL = 5
        }
    }
