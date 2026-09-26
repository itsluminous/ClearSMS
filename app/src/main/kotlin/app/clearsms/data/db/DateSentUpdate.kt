package app.clearsms.data.db

/**
 * Partial-entity payload for [MessageDao.setDateSentBatch]: the row to
 * touch and the sender timestamp to record on it. Room writes ONLY these
 * two columns (matching on the primary key), so a backfill page updates
 * many rows in one transaction without rewriting anything else.
 */
data class DateSentUpdate(
    val id: Long,
    val dateSent: Long,
)
