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
     * Phones the sender (GitHub #99). By default it opens the dialer
     * PREFILLED with the number via ACTION_DIAL and stops - the maintainer's
     * explicit stance against one-click calling; the user presses the
     * dialer's own call button. With the opt-in "Direct call" setting on
     * (Settings → Messages, default OFF) and the CALL_PHONE permission
     * granted, it places the call immediately instead - decided at tap
     * time by CallActionActivity through the same DirectCall mapping the
     * conversation title bar uses, so the two surfaces always agree.
     * Offered only when the sender is a number a person would dial
     * (DialableNumber), the same rule as the title bar's call button -
     * short codes and alphanumeric sender ids get no button at all.
     * Declared right after REPLY so the two "respond to this person"
     * buttons sit together and the housekeeping pair (MARK_READ, DELETE)
     * keeps its place.
     */
    CALL,

    /** Forwards the message text via ACTION_SEND. Available but OFF by default. */
    SHARE,
    COPY_OTP,
    SHARE_OTP,
}
