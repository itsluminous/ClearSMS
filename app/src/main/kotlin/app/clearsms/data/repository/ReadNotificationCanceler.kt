package app.clearsms.data.repository

/**
 * Platform hook cancelling shade notifications for messages that stopped
 * being "new" — marked read through any in-app path, or deleted.
 *
 * Implemented by the notification layer (which owns the id derivation); the
 * repository decides WHICH message/thread ids are affected — ViewModels never
 * enumerate notification ids. Null in tests, like [SystemSmsDeleter].
 */
interface ReadNotificationCanceler {
    /**
     * Cancels every per-message notification (transaction, OTP, scam) posted
     * for [messageIds], then reaps any group summary left without children.
     */
    fun cancelFor(messageIds: List<Long>)

    /**
     * Cancels the per-thread message notification for each of [threadIds].
     * Callers pass only threads with NO unread messages left — a partially
     * read thread keeps its notification.
     */
    fun cancelThreads(threadIds: List<Long>)
}
