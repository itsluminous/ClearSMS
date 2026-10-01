package app.clearsms.data.db

/**
 * THE one definition of "the representative (newest) message of each
 * thread" that every per-thread list in [MessageDao] joins through - the
 * inbox and its paged variants, select-all ids, the unread badge, the
 * archived list and its ids.
 *
 * The representative is the newest MESSAGE, never the newest ROW: it is the
 * row that sorts first under the conversation pager's own key - the time
 * key DESC, then `id` DESC purely as a tie-break for equal instants - so the
 * inbox preview can never disagree with the bubble at the bottom of the
 * thread. Selecting by `MAX(id)` (Room insert order) used to be the bug: it
 * is right only while insert order happens to match message order, which
 * is exactly what old threads break (sent-time backfills, catch-up imports,
 * provider-id reuse), so only old threads previewed their first message.
 *
 * Shape (two grouped passes, no window functions - minSdk 23 SQLite has
 * none):
 *   1. `newest` finds each thread's maximum key among live rows
 *      (`deletedAt IS NULL`, so a binned message never represents a thread).
 *   2. the rows AT that maximum are looked up through the `(threadId,
 *      timestamp)` index - an equality probe per thread, not a scan - and
 *      `MAX(id)` among only those ties picks the representative.
 * Pass 1 is the same grouped scan the old query already paid for; pass 2
 * adds one index probe per thread; the outer join is a primary-key lookup.
 * The `CROSS JOIN` is SQLite's documented planner hint that the left side
 * (`newest`, one row per thread) is the OUTER loop: without it the
 * unanalysed planner put `messages` outside and re-walked every live row
 * against an automatic index on `newest`, i.e. a second full pass.
 * `InboxPreviewRowDaoTest` asserts the resulting plan.
 *
 * Each caller supplies its own `FROM messages m` alias and its own
 * `WHERE` filters (archived / unread / category) - only the choice of row
 * lives here. [JOIN_BY_RECEIVED] is the canonical representative (the
 * indexed received time, the default ordering); [JOIN_BY_SENT] is the same
 * template under the sent-time key for the inbox pager that ranks by the
 * sender's time (#45), so the preview under that setting is the message
 * that pager shows at the bottom. Both constants are literal
 * concatenations of the same parts, and [LatestPerThreadContractTest]
 * proves each equals [joinFor] of its key.
 */
object LatestPerThreadSql {
    /** The received-time key: when this device got the message (indexed). */
    const val RECEIVED_KEY = "timestamp"

    /** The sent-time key of `pagingThreadBySent`: the network timestamp, else the received time. */
    const val SENT_KEY = "COALESCE(dateSent, timestamp)"

    private const val PART_BEFORE_MAX_KEY =
        """INNER JOIN (
            SELECT messages.threadId AS threadId, MAX(messages.id) AS maxId
            FROM (
                SELECT threadId, MAX("""

    private const val PART_BEFORE_KEY_MATCH =
        """) AS maxKey
                FROM messages
                WHERE deletedAt IS NULL
                GROUP BY threadId
            ) newest
            CROSS JOIN messages ON messages.threadId = newest.threadId AND """

    private const val PART_AFTER_KEY_MATCH =
        """ = newest.maxKey
            WHERE messages.deletedAt IS NULL
            GROUP BY messages.threadId
        ) latest ON latest.threadId = m.threadId AND latest.maxId = m.id"""

    /** Join fragment selecting each thread's newest message by received time, `id` as tie-break. */
    const val JOIN_BY_RECEIVED =
        PART_BEFORE_MAX_KEY + RECEIVED_KEY + PART_BEFORE_KEY_MATCH + RECEIVED_KEY + PART_AFTER_KEY_MATCH

    /** The same fragment under the sent-time key, for the sent-ordered inbox pager. */
    const val JOIN_BY_SENT =
        PART_BEFORE_MAX_KEY + SENT_KEY + PART_BEFORE_KEY_MATCH + SENT_KEY + PART_AFTER_KEY_MATCH

    /**
     * The template the two constants are built from, for the contract test
     * (annotation arguments must be compile-time constants, so the DAO
     * cannot call this itself).
     */
    fun joinFor(key: String): String = PART_BEFORE_MAX_KEY + key + PART_BEFORE_KEY_MATCH + key + PART_AFTER_KEY_MATCH
}
