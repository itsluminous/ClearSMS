package app.clearsms.domain.model

/**
 * The instant a message sorts and displays at under [MessageSortOrder] -
 * the pure, in-memory twin of the DAO's `ORDER BY timestamp` /
 * `ORDER BY COALESCE(dateSent, timestamp)` keys, so mappers, separators
 * and tests all agree with the queries.
 *
 * @param receivedMs when this device received (or sent) the message.
 * @param dateSentMs the sender's network timestamp, null when unknown.
 */
fun MessageSortOrder.sortTimestamp(
    receivedMs: Long,
    dateSentMs: Long?,
): Long =
    when (this) {
        MessageSortOrder.RECEIVED -> receivedMs
        MessageSortOrder.SENT -> dateSentMs ?: receivedMs
    }

/**
 * Newest-first comparator matching the conversation pager's key under
 * [order] (time DESC, then `id` DESC), for in-memory ordering and tests:
 * same tie-break as the SQL, so a same-instant run keeps a deterministic,
 * stable order under either setting.
 */
fun <T> newestFirstComparator(
    order: MessageSortOrder,
    id: (T) -> Long,
    receivedMs: (T) -> Long,
    dateSentMs: (T) -> Long?,
): Comparator<T> =
    compareByDescending<T> { order.sortTimestamp(receivedMs(it), dateSentMs(it)) }
        .thenByDescending { id(it) }

/**
 * Normalizes a raw provider / PDU sent timestamp: the provider stores 0 when
 * the network reported none, and a negative value is garbage - both read as
 * UNKNOWN (null) rather than a message "sent at the epoch". Nothing else is
 * corrected: an SMSC clock that is skewed is still what the network said,
 * and it is shown labelled as such rather than second-guessed.
 */
fun sentTimestampOrNull(rawMs: Long?): Long? = rawMs?.takeIf { it > 0L }
