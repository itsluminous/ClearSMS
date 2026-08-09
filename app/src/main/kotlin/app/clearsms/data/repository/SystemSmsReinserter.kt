package app.clearsms.data.repository

/**
 * Platform hook re-inserting a restored message into the system SMS
 * provider (recycle-bin restore). Null in tests; implemented by
 * `TelephonyWriter`, whose writes no-op off the default SMS app.
 */
interface SystemSmsReinserter {
    /**
     * Re-inserts an incoming message into the provider inbox.
     *
     * @return the new provider row id, or null when the insert failed or
     *   Clear SMS is not the default app.
     */
    fun reinsertInbox(
        sender: String,
        body: String,
        timestampMs: Long,
        read: Boolean,
    ): Long?

    /** Re-inserts an outgoing message into the provider sent box. */
    fun reinsertSent(
        destination: String,
        body: String,
        timestampMs: Long,
    ): Long?
}
