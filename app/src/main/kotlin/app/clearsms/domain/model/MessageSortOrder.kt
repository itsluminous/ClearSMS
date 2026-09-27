package app.clearsms.domain.model

/**
 * Which instant orders conversations and the messages inside them
 * (GitHub #45).
 *
 * [RECEIVED] is the default and today's behaviour: when THIS device got the
 * message. Nothing reshuffles on update. [SENT] orders by the sender's
 * network timestamp (`DATE_SENT`) where one is known - so several messages
 * that arrive together after a signal gap or flight mode land in the order
 * they were actually sent - and falls back to the received time for any
 * message without one, so the order stays total and nothing sinks to the
 * epoch. Also applies to what the bubbles and date separators show, so the
 * displayed times are never out of order with the sort.
 */
enum class MessageSortOrder {
    RECEIVED,
    SENT,
}
