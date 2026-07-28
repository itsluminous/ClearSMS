package app.clearsms.domain.model

/**
 * Action buttons the user can pin onto message notifications.
 *
 * Declaration order matters: it defines the deterministic button order on a
 * notification (see NotificationActionPlanner).
 */
enum class NotificationAction {
    MARK_READ,
    DELETE,
    REPLY,

    /** Forwards the message text via ACTION_SEND. Available but OFF by default. */
    SHARE,
    COPY_OTP,
    SHARE_OTP,
}
