package app.clearsms.data.db

/**
 * Lifecycle of an incoming MMS row. Null on SMS rows; MMS rows move
 * PENDING -> DOWNLOADED on a successful carrier retrieval, or PENDING ->
 * FAILED after the retry is exhausted (a FAILED row stays visible and is
 * tappable to retry, which flips it back to PENDING).
 *
 * An MMS the history import read out of the system provider is born
 * DOWNLOADED whichever direction it travelled - a sent one too, so that a
 * text-only sent MMS is still recognisable as MMS. An MMS sent from this
 * app itself carries no status (its lifecycle is [DeliveryStatus]).
 */
enum class MmsStatus {
    /** Notification stored; the MMSC download is in flight. */
    PENDING,

    /** Content retrieved, parsed and stored (body + attachments final). */
    DOWNLOADED,

    /** Download failed twice; the row renders as "could not be downloaded". */
    FAILED,
}
