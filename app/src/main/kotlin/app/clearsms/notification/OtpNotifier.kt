package app.clearsms.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.Spannable
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.clearsms.R
import app.clearsms.data.db.MessageEntity
import app.clearsms.domain.model.NotificationAction
import app.clearsms.domain.model.OtpDisplaySize
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Heads-up notification for a received OTP.
 *
 * The code is rendered as spaced digits in the title, scaled according to the
 * user's OTP display-size setting, with the full message in a [BigTextStyle]
 * body. Copy is ALWAYS available; the remaining actions honor the user's
 * notification-action selection (see [NotificationActionPlanner.forOtp]).
 *
 * LOCKSCREEN: the OTP digits are the title, so the notification is
 * [NotificationCompat.VISIBILITY_PRIVATE] with a digit-free public version
 * ("New OTP from <sender>"). This is the default with no setting — leaking
 * codes to anyone who can see the locked screen defeats the point of an OTP,
 * so private-by-default is the safer choice.
 */
@Singleton
class OtpNotifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun notify(
            message: MessageEntity,
            otp: String,
            displaySize: OtpDisplaySize,
            selected: Set<NotificationAction> = MessageNotifier.DEFAULT_SELECTED,
        ) {
            Channels.ensureCreated(context)
            try {
                NotificationManagerCompat
                    .from(context)
                    .notify(notificationId(message.id), build(message, otp, displaySize, selected))
            } catch (_: SecurityException) {
                // POST_NOTIFICATIONS not granted; onboarding asks for it.
            }
        }

        /** Builds the notification; internal so tests can inspect it without posting. */
        internal fun build(
            message: MessageEntity,
            otp: String,
            displaySize: OtpDisplaySize,
            selected: Set<NotificationAction>,
        ): Notification {
            val title = buildTitle(otp, displaySize)
            val publicVersion =
                NotificationCompat
                    .Builder(context, Channels.OTP)
                    .setSmallIcon(R.drawable.ic_notification)
                    // Digit-free on purpose: no OTP on the lockscreen.
                    .setContentTitle(context.getString(R.string.otp_public_title, message.sender))
                    .build()
            val builder =
                NotificationCompat
                    .Builder(context, Channels.OTP)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title)
                    .setContentText(context.getString(R.string.otp_from, message.sender))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message.body))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setPublicVersion(publicVersion)
                    .setAutoCancel(true)
            for (action in NotificationActionPlanner.forOtp(selected)) {
                when (action) {
                    NotificationAction.COPY_OTP ->
                        builder.addAction(
                            0,
                            context.getString(R.string.action_copy),
                            otpAction(OtpActionReceiver.ACTION_COPY, message, otp),
                        )
                    NotificationAction.SHARE_OTP ->
                        builder.addAction(
                            0,
                            context.getString(R.string.action_share),
                            otpAction(OtpActionReceiver.ACTION_SHARE, message, otp),
                        )
                    NotificationAction.DELETE ->
                        builder.addAction(
                            0,
                            context.getString(R.string.action_delete),
                            otpAction(OtpActionReceiver.ACTION_DELETE, message, otp),
                        )
                    NotificationAction.MARK_READ ->
                        MessageActionFactory
                            .build(context, message, notificationId(message.id), listOf(NotificationAction.MARK_READ))
                            .forEach(builder::addAction)
                    // Planner never emits REPLY for OTP notifications.
                    NotificationAction.REPLY -> Unit
                }
            }
            return builder.build()
        }

        fun cancel(messageId: Long) {
            NotificationManagerCompat.from(context).cancel(notificationId(messageId))
        }

        private fun otpAction(
            action: String,
            message: MessageEntity,
            otp: String,
        ): PendingIntent {
            val intent =
                Intent(context, OtpActionReceiver::class.java)
                    .setAction(action)
                    .putExtra(OtpActionReceiver.EXTRA_MESSAGE_ID, message.id)
                    .putExtra(OtpActionReceiver.EXTRA_OTP, otp)
            val requestOffset =
                when (action) {
                    OtpActionReceiver.ACTION_COPY -> 0
                    OtpActionReceiver.ACTION_SHARE -> 1
                    else -> 2
                }
            return PendingIntent.getBroadcast(
                context,
                ((message.id % 100_000) * 4 + requestOffset).toInt(),
                intent,
                NotificationIntents.flags(),
            )
        }

        private fun notificationId(messageId: Long) = OTP_NOTIFICATION_ID_BASE + (messageId % 1000).toInt()

        companion object {
            private const val OTP_NOTIFICATION_ID_BASE = 10_000

            /** "123456" → "1 2 3 4 5 6", bold and scaled per [displaySize]. */
            fun buildTitle(
                otp: String,
                displaySize: OtpDisplaySize,
            ): CharSequence {
                val spaced = otp.toCharArray().joinToString(" ")
                val spannable = SpannableString(spaced)
                spannable.setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, spaced.length, Spannable.SPAN_INCLUSIVE_EXCLUSIVE)
                val scale =
                    when (displaySize) {
                        OtpDisplaySize.DEFAULT -> 1.0f
                        OtpDisplaySize.OPTION_A -> 1.1f
                        OtpDisplaySize.OPTION_B -> 1.25f
                        OtpDisplaySize.OPTION_C -> 1.4f
                        OtpDisplaySize.OPTION_D -> 1.6f
                    }
                if (scale != 1.0f) {
                    spannable.setSpan(RelativeSizeSpan(scale), 0, spaced.length, Spannable.SPAN_INCLUSIVE_EXCLUSIVE)
                }
                return spannable
            }
        }
    }
