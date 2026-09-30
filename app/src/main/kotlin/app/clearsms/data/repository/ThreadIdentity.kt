package app.clearsms.data.repository

import app.clearsms.data.db.MessageDao
import app.clearsms.data.db.ThreadAnchor

/**
 * Which app thread a message belongs to (issue #42: one thread per person).
 *
 * Two sources of identity, in a fixed order:
 *
 * 1. **The platform's thread id** (`Telephony.Sms.THREAD_ID`), the PRIMARY
 *    key. The system provider matches addresses canonically for every
 *    region, which is why QKSMS merges `+48 601 234 567` with
 *    `601 234 567` and this app used to split them. A message whose
 *    provider thread already anchors an app thread joins that thread.
 * 2. **The region-aware sender key** ([SenderNormalizer.normalize]) as the
 *    FALLBACK: the row has no provider thread (an MMS - never mirrored to
 *    the provider; a provider write that failed; a provider that ignored
 *    the projection; a pre-#42 row whose provider row is gone), or its
 *    provider thread anchors nothing yet. Then a thread with the same key
 *    is joined, else a new thread is opened.
 *
 * Two guards keep the platform's threads from making things WORSE:
 *
 * - An anchor is only ever taken from a **one-to-one phone-number**
 *   conversation ([anchorFor]). Alphanumeric ids and short codes never
 *   carry one: the provider keeps `VM-HDFCBK` and `AD-HDFCBK` apart, the
 *   app's route-variant rule merges them, and the app's rule wins - so a
 *   short code cannot be pulled into a phone thread either. An MMS with
 *   several recipients never carries one: the app has no group threads and
 *   a group thread must not swallow its members' one-to-one threads.
 * - An anchor is only **honoured** when the anchored thread is loosely the
 *   same number ([PhoneNumberKey.looselySame]) - two dialling variants of
 *   one person pass, two different people whose SMS an OEM provider filed
 *   under one (group) thread do not; such an anchor is ignored and the row
 *   falls back to its sender key. The provider thus can only ever MERGE
 *   variants of one number, never people.
 */
object ThreadIdentity {
    /**
     * The provider thread id a row may anchor to, or null when the row must
     * not anchor anything: no provider thread, a non-phone sender, or a
     * message addressed to more than one party.
     */
    fun anchorFor(
        sender: String,
        providerThreadId: Long?,
        recipientCount: Int = 1,
    ): Long? {
        if (providerThreadId == null || providerThreadId <= 0L) return null
        if (recipientCount > 1) return null
        if (!SenderNormalizer.isPhoneNumber(sender)) return null
        return providerThreadId
    }

    /**
     * The app thread the anchored rows [anchored] make [normalized] join,
     * or null when the anchor is unusable (nothing anchored, or the anchor
     * is a group of different people). Pure: the decision the resolver and
     * the migration both apply.
     */
    fun threadFromAnchor(
        normalized: String,
        anchored: List<ThreadAnchor>,
    ): Long? {
        if (anchored.isEmpty()) return null
        val same = anchored.firstOrNull { it.normalizedSender == normalized }
        if (same != null) return same.threadId
        return anchored.firstOrNull { PhoneNumberKey.looselySame(it.normalizedSender, normalized) }?.threadId
    }

    /**
     * Resolves the thread for a message about to be inserted: anchor, then
     * sender key, then a fresh id. Must run inside the insert's transaction
     * (the fresh id is `MAX(threadId) + 1`).
     */
    suspend fun resolve(
        dao: MessageDao,
        sender: String,
        normalized: String,
        providerThreadId: Long?,
        recipientCount: Int = 1,
    ): Long {
        val anchor = anchorFor(sender, providerThreadId, recipientCount)
        if (anchor != null) {
            threadFromAnchor(normalized, dao.threadsAnchoredTo(anchor))?.let { return it }
        }
        return dao.threadIdFor(normalized) ?: ((dao.maxThreadId() ?: 0L) + 1L)
    }
}
