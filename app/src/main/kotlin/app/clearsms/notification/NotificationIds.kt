package app.clearsms.notification

/**
 * Single source of truth for shade notification ids and group keys.
 *
 * Posting (the notifiers) and cancellation ([NotificationDismisser]) MUST both
 * derive ids from here - the original defect was exactly a missing cancel path
 * that could not know which ids the post path had used. Each notifier family
 * gets a disjoint id range so a message's OTP, transaction and scam
 * notifications never collide with each other or with per-thread message
 * notifications.
 */
object NotificationIds {
    /** OTP notifications: one per message. */
    const val OTP_BASE = 10_000

    /** Regular message notifications: one per THREAD (MessagingStyle). */
    const val MESSAGE_THREAD_BASE = 20_000

    /** Parsed transaction/balance/bill notifications: one per message. */
    const val TRANSACTION_BASE = 30_000

    /** The single group-summary notification for the transaction group. */
    const val TRANSACTION_GROUP_SUMMARY = 31_000

    /** Scam warnings: one per message. */
    const val SCAM_BASE = 40_000

    /** Singleton "message failed to send" notification. */
    const val SEND_FAILURE = 50_001

    /** Shade group collapsing bursts of transaction notifications. */
    const val TRANSACTION_GROUP_KEY = "app.clearsms.TRANSACTIONS"

    fun otp(messageId: Long): Int = OTP_BASE + (messageId % 1000).toInt()

    fun messageThread(threadId: Long): Int = MESSAGE_THREAD_BASE + (threadId % 10_000).toInt()

    fun transaction(messageId: Long): Int = TRANSACTION_BASE + (messageId % 1000).toInt()

    fun scam(messageId: Long): Int = SCAM_BASE + (messageId % 1000).toInt()
}
