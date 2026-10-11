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

    /**
     * Opens the dialer PREFILLED with the sender's number via ACTION_DIAL
     * (GitHub #99). It never places the call and needs no CALL_PHONE
     * permission - the maintainer's explicit stance against one-click
     * calling; the user presses the dialer's own call button. Offered only
     * when the sender is a number a person would dial (DialableNumber), the
     * same rule as the conversation title bar's call button - short codes
     * and alphanumeric sender ids get no button at all. Declared right
     * after REPLY so the two "respond to this person" buttons sit together
     * and the housekeeping pair (MARK_READ, DELETE) keeps its place.
     */
    CALL,

    /** Forwards the message text via ACTION_SEND. Available but OFF by default. */
    SHARE,
    COPY_OTP,
    SHARE_OTP,
}
