package app.clearsms.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import app.clearsms.R

/** Notification channel ids and one-shot channel registration. */
object Channels {
    const val OTP = "otp"
    const val MESSAGES = "messages"
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
                NotificationChannel(
                    OTP,
                    context.getString(R.string.channel_otp),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = context.getString(R.string.channel_otp_desc) },
                NotificationChannel(
                    MESSAGES,
                    context.getString(R.string.channel_messages),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = context.getString(R.string.channel_messages_desc) },
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
