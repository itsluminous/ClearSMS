package app.clearsms.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import app.clearsms.R

/** Notification channel ids and one-shot channel registration. */
object Channels {
    const val OTP = "otp"
    const val MESSAGES = "messages"
    const val PROMOTIONS = "promotions"
    const val TRANSACTIONS = "transactions"
    const val SECURITY = "security"
    const val SUMMARY = "summary"
    const val SYNC = "sync"

    /** Creates all channels; safe to call repeatedly. No-op before Android O. */
    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(
            listOf(
                // HIGH importance so OTPs show as heads-up notifications.
                // PRIVATE lockscreen visibility keeps the code itself off the
                // lockscreen (the notification also carries a digit-free
                // public version).
                NotificationChannel(
                    OTP,
                    context.getString(R.string.channel_otp),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.channel_otp_desc)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
                NotificationChannel(
                    MESSAGES,
                    context.getString(R.string.channel_messages),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = context.getString(R.string.channel_messages_desc) },
                // Promotional messages are opt-in: the in-app setting is off by
                // default, and this channel is LOW importance (silent, no
                // heads-up) so even when enabled it never interrupts. Users can
                // also block it from Android's own notification categories.
                NotificationChannel(
                    PROMOTIONS,
                    context.getString(R.string.channel_promotions),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = context.getString(R.string.channel_promotions_desc) },
                // DEFAULT (not HIGH) on purpose: parsed transaction alerts
                // are informative, not urgent, and must not heads-up.
                NotificationChannel(
                    TRANSACTIONS,
                    context.getString(R.string.channel_transactions),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = context.getString(R.string.channel_transactions_desc) },
                NotificationChannel(
                    SECURITY,
                    context.getString(R.string.channel_security),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = context.getString(R.string.channel_security_desc) },
                NotificationChannel(
                    SUMMARY,
                    context.getString(R.string.channel_summary),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = context.getString(R.string.channel_summary_desc) },
                NotificationChannel(
                    SYNC,
                    context.getString(R.string.channel_sync),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = context.getString(R.string.channel_sync_desc) },
            ),
        )
    }
}
