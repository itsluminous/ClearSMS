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

    /**
     * Promotions category id.
     *
     * Versioned on purpose: v0.5.2 shipped this channel as `promotions` with
     * IMPORTANCE_LOW, which Android surfaces as ON. A channel's importance is
     * user-owned once created — re-creating the same id with a lower importance
     * is ignored — so the only way to actually ship "off by default" to devices
     * that already have it is a NEW id. [LEGACY_PROMOTIONS] is deleted below so
     * users aren't left with two Promotions entries in system settings.
     */
    const val PROMOTIONS = "promotions_v2"

    private const val LEGACY_PROMOTIONS = "promotions"
    const val TRANSACTIONS = "transactions"
    const val SECURITY = "security"
    const val SUMMARY = "summary"
    const val SYNC = "sync"

    /** Creates all channels; safe to call repeatedly. No-op before Android O. */
    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // Drop the v0.5.2 "promotions" channel, which was created enabled
        // (IMPORTANCE_LOW). Its replacement below uses a new id so it can
        // genuinely start blocked; deleting the old one avoids a duplicate,
        // still-enabled Promotions entry in system settings.
        manager.deleteNotificationChannel(LEGACY_PROMOTIONS)
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
                // Promotions are OFF by default and controlled solely from
                // Android's notification settings for the app — there is no
                // in-app toggle. IMPORTANCE_NONE creates the category in a
                // blocked state, so it is visible (and switchable on) in system
                // settings while showing nothing until the user asks for it.
                // Importance is user-owned once created: this initial value
                // only applies to installs that don't have the channel yet, and
                // a user who enables it keeps it enabled across updates.
                NotificationChannel(
                    PROMOTIONS,
                    context.getString(R.string.channel_promotions),
                    NotificationManager.IMPORTANCE_NONE,
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
