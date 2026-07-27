package app.clearsms.notification

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
import app.clearsms.domain.model.OtpDisplaySize
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Heads-up notification for a received OTP.
 *
 * The code is rendered as spaced digits in the title, scaled according to the
 * user's OTP display-size setting, with the full message in a [BigTextStyle]
 * body and Copy / Share / Delete actions handled by [OtpActionReceiver].
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
        ) {
            Channels.ensureCreated(context)
            val title = buildTitle(otp, displaySize)
            val notification =
                NotificationCompat
                    .Builder(context, Channels.OTP)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title)
                    .setContentText(context.getString(R.string.otp_from, message.sender))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message.body))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setAutoCancel(true)
                    .addAction(0, context.getString(R.string.action_copy), action(OtpActionReceiver.ACTION_COPY, message, otp))
                    .addAction(0, context.getString(R.string.action_share), action(OtpActionReceiver.ACTION_SHARE, message, otp))
                    .addAction(0, context.getString(R.string.action_delete), action(OtpActionReceiver.ACTION_DELETE, message, otp))
                    .build()
            try {
                NotificationManagerCompat.from(context).notify(notificationId(message.id), notification)
            } catch (_: SecurityException) {
                // POST_NOTIFICATIONS not granted; onboarding asks for it.
            }
        }

        fun cancel(messageId: Long) {
            NotificationManagerCompat.from(context).cancel(notificationId(messageId))
        }

        private fun action(
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
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
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
