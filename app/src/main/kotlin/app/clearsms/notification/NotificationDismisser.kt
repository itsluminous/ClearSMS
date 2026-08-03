package app.clearsms.notification

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import app.clearsms.data.repository.ReadNotificationCanceler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cancels shade notifications for messages the user has now seen in-app.
 *
 * The notifiers post from the SMS receiver context and this cancels from UI
 * flows — both sides go through [NotificationManagerCompat] against ids
 * derived by [NotificationIds], the shared single source of truth, so a
 * cancel always hits exactly what the post created.
 *
 * After cancelling children it reaps an orphaned transaction group summary:
 * the summary is posted once per burst and the system does not reliably
 * remove it when its last child is cancelled programmatically, so a lone
 * summary would otherwise linger in the shade.
 */
@Singleton
class NotificationDismisser
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ReadNotificationCanceler {
        override fun cancelFor(messageIds: List<Long>) {
            if (messageIds.isEmpty()) return
            val manager = NotificationManagerCompat.from(context)
            for (id in messageIds) {
                manager.cancel(NotificationIds.transaction(id))
                manager.cancel(NotificationIds.otp(id))
                manager.cancel(NotificationIds.scam(id))
            }
            reapOrphanTransactionSummary(manager)
        }

        override fun cancelThreads(threadIds: List<Long>) {
            if (threadIds.isEmpty()) return
            val manager = NotificationManagerCompat.from(context)
            threadIds.forEach { manager.cancel(NotificationIds.messageThread(it)) }
        }

        /**
         * Cancels the transaction group summary when no child of the group is
         * left in the shade; keeps it while any child remains (partial reads).
         */
        private fun reapOrphanTransactionSummary(manager: NotificationManagerCompat) {
            val notificationManager =
                context.getSystemService(NotificationManager::class.java) ?: return
            val active =
                try {
                    notificationManager.activeNotifications
                } catch (_: Exception) {
                    // Shade state unavailable (rare platform failure): leave the
                    // summary rather than guess.
                    return
                }
            val hasChild =
                active.any { sbn ->
                    sbn.id != NotificationIds.TRANSACTION_GROUP_SUMMARY &&
                        sbn.notification.group == NotificationIds.TRANSACTION_GROUP_KEY
                }
            if (!hasChild) manager.cancel(NotificationIds.TRANSACTION_GROUP_SUMMARY)
        }
    }
